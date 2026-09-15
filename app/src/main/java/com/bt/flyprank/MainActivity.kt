package com.bt.flyprank

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bt.flyprank.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private companion object {
        /**
         * 同时上屏的数量上限 = min(品种数 × 每種上限, FlySpecies.MAX_TOTAL)。
         * 和布局里 sliderCount 的 valueTo、FlyService.MAX_FLIES 保持一致。
         */
        const val MAX_FLIES = 50

        /**
         * 预览区最多同时画几只。
         * 预览框只有 150dp 高，虫子全塞进去会糊成一团，
         * 而且这个 View 每帧都在跑绘制，太多只会白耗电。
         * 超过这个数时按轮转取前几只，品种仍然尽量分散。
         */
        const val PREVIEW_MAX = 10

        /** 新装/更新后自动放出的延后时间：让界面先画出来，避免黑屏期就放虫。 */
        const val AUTO_RELEASE_DELAY_MS = 700L
    }

    /** 是否正在"等用户授权，授权完就自动放出"。 */
    private var awaitingAutoRelease = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchFlies() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 品种数和上限从代码取，改品种表后界面文案不会再对不上
        b.textLimitHint.text = getString(
            R.string.hint_max_per_species,
            FlySpecies.count(),
            FlySpecies.MAX_PER_SPECIES,
            FlySpecies.maxTotal()
        )

        val p = FlyService.prefs(this)

        // ---- 新装 / 刚更新：自动把数量拉满，并标记这一轮要自动放出 ----
        val firstRunAfterInstall = consumeVersionChange(p)
        if (firstRunAfterInstall) {
            p.edit().putInt(FlyService.KEY_COUNT, FlySpecies.maxTotal()).apply()
        }

        // ---- 数量 ----
        b.sliderCount.value = p.getInt(FlyService.KEY_COUNT, FlySpecies.maxTotal()).toFloat()
        applyCount(b.sliderCount.value.toInt())
        b.sliderCount.addOnChangeListener { _, value, _ ->
            val n = value.toInt().coerceIn(1, MAX_FLIES)
            applyCount(n)
            p.edit().putInt(FlyService.KEY_COUNT, n).apply()
        }

        // ---- 体型已固定，不再提供滑块 ----
        // 大小由 FlyService.FIXED_SIZE_SCALE 决定（0.5 倍），
        // 品种之间的相对大小仍由 FlySpecies.sizeScale 决定。
        refreshPreview()

        // ---- 声音 ----
        b.switchSound.isChecked = p.getBoolean(FlyService.KEY_SOUND, true)
        b.switchSound.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean(FlyService.KEY_SOUND, checked).apply()
        }

        // ---- 振动 ----
        b.switchHaptic.isChecked = p.getBoolean(FlyService.KEY_HAPTIC, true)
        b.switchHaptic.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean(FlyService.KEY_HAPTIC, checked).apply()
        }

        b.buttonPermission.setOnClickListener { requestOverlay() }
        b.buttonLaunch.setOnClickListener { onPrimaryAction() }
        b.buttonInfo.setOnClickListener {
            Toast.makeText(this, R.string.how_to_stop, Toast.LENGTH_LONG).show()
        }

        applyWindowInsets()

        // 自动放出：延后一点执行，等界面先画出来，
        // 否则会出现在"黑屏时就已经把虫子放出去"的突兀感
        if (firstRunAfterInstall) {
            b.root.postDelayed({ autoRelease() }, AUTO_RELEASE_DELAY_MS)
        }
    }

    /**
     * 判断这次是不是"新装或刚更新"，并顺手把版本号记下来。
     *
     * Android **没有**"安装完成后自动启动"这种能力 —— 系统不会给应用自己发
     * 任何安装完成广播，第三方也拉不起刚装好的包。所以"装完就能看到虫子"
     * 只能靠两条路：
     *   1. 用 adb 装的话，脚本装完立刻 `am start`（install-apk.ps1 已实现）
     *   2. 用户点开图标后**不再需要手点按钮** —— 就是这个方法在做的事
     *
     * 用 versionCode 判断比用"首次启动"标志更准：重新打包安装（版本号不变）时
     * 不会重复触发，而真正升级到新版本会触发一次，符合直觉。
     */
    private fun consumeVersionChange(p: android.content.SharedPreferences): Boolean {
        val current = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Throwable) {
            return false
        }
        val last = p.getLong(FlyService.KEY_LAST_VERSION, -1L)
        if (last == current) return false
        p.edit().putLong(FlyService.KEY_LAST_VERSION, current).apply()
        return true
    }

    /**
     * 新装/更新后的自动放出。
     *
     * 已经授权 -> 直接放满；没授权 -> 把授权页**主动推**到用户面前，
     * 并明确告诉他"点一下回来就会自动放出"，省掉一次找按钮的操作。
     * 授权结果无法在此刻获知，所以授权后由 [onResume] 里的
     * [maybeAutoReleaseAfterGrant] 接管。
     */
    private fun autoRelease() {
        if (FlyService.running) return
        if (FlyService.canDrawOverlays(this)) {
            launchFlies()
        } else {
            awaitingAutoRelease = true
            Toast.makeText(this, R.string.auto_release_need_permission, Toast.LENGTH_LONG).show()
            requestOverlay()
        }
    }

    /**
     * 从授权页返回时检查：如果之前是在等授权自动放出，现在有权限了就立刻放。
     * 放在 onResume 里而不是去读 onActivityResult —— 因为授权页是系统页面，
     * 用 startActivity 跳过去的，拿不到结果回调。
     */
    private fun maybeAutoReleaseAfterGrant() {
        if (!awaitingAutoRelease) return
        if (!FlyService.canDrawOverlays(this)) return
        awaitingAutoRelease = false
        if (FlyService.running) return
        b.root.postDelayed({ launchFlies() }, 300)
    }

    /**
     * 处理状态栏/手势条留白。
     *
     * Android 15（API 35）起，targetSdk 35 的应用默认是**边到边**绘制，
     * 内容会直接顶到状态栏底下，标题会被时间和图标压住。
     * 这里按版本分别处理：
     *   API 35+ 用 setOnApplyWindowInsetsListener 拿到真实 inset
     *   更早的版本系统会自动留白，不用管
     * 不引入 androidx.core 的 ViewCompat 是为了少一层依赖，行为一样。
     */
    private fun applyWindowInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val scroll = b.root.getChildAt(0) as? View ?: return
        val basePad = (20 * resources.displayMetrics.density).toInt()
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(basePad, basePad + bars.top, basePad, basePad + bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        // 从系统授权页返回时，如果之前在等授权自动放出，这里补上
        maybeAutoReleaseAfterGrant()
    }

    /**
     * 数量变化后同时更新三处：数量标签、品种汇总文字、预览画布。
     * 三者都来自 FlySpecies.allocate，所以永远一致。
     */
    private fun applyCount(n: Int) {
        b.labelCount.text = getString(R.string.count_fmt, n)
        b.previewCount.text = getString(R.string.preview_count_fmt, n)

        val roster = FlySpecies.allocate(n)
        // 品种汇总（最多 50 只时逐个列名字会铺满屏幕）
        val order = LinkedHashMap<String, Int>()
        for (sp in roster) order[sp.displayName] = (order[sp.displayName] ?: 0) + 1
        b.textSpecies.text = order.entries
            .joinToString("、") { (name, c) -> if (c > 1) "$name×$c" else name }

        refreshPreview()
    }

    /** 把当前数量送进预览画布（体型已固定）。 */
    private fun refreshPreview() {
        val n = b.sliderCount.value.toInt().coerceIn(1, MAX_FLIES)
        val roster = FlySpecies.allocate(n).take(PREVIEW_MAX)
        b.preview.setRoster(roster)
    }

    private fun refreshUi() {
        val granted = FlyService.canDrawOverlays(this)
        val running = FlyService.running

        // 已授权就把整张权限卡收起来 —— 不占地方，也不干扰
        b.permissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        if (!granted) {
            b.statusDot.setBackgroundResource(R.drawable.dot_warn)
            b.statusText.setText(R.string.permission_title)
        }

        // 主按钮反映真实状态：没权限时不去假装能放虫
        when {
            running -> {
                b.buttonLaunch.setText(R.string.action_stop)
                b.launchHint.setText(R.string.hint_running)
            }
            !granted -> {
                b.buttonLaunch.setText(R.string.action_grant_first)
                b.launchHint.setText(R.string.hint_need_permission)
            }
            else -> {
                b.buttonLaunch.setText(R.string.action_launch)
                b.launchHint.setText(R.string.hint_idle)
            }
        }
    }

    private fun requestOverlay() {
        if (FlyService.canDrawOverlays(this)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Throwable) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    /**
     * 主按钮的行为要和它显示的文字一致：
     * 显示「先授予权限」时就直接去授权页，而不是弹个 toast 再跳 —— 
     * 那等于点两次才走一步。
     */
    private fun onPrimaryAction() {
        if (FlyService.running) {
            FlyService.stop(this)
            b.root.postDelayed({ refreshUi() }, 260)
            return
        }
        if (!FlyService.canDrawOverlays(this)) {
            requestOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // 通知权限只影响那条"收网"快捷通知，拒绝了也照样放虫
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchFlies()
    }

    private fun launchFlies() {
        FlyService.start(this)
        Toast.makeText(this, R.string.launched_toast, Toast.LENGTH_SHORT).show()
        b.root.postDelayed({ refreshUi() }, 400)
    }
}
