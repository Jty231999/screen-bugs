package com.bt.flyprank

/**
 * 虫子品种表。
 *
 * 集中定义「长什么样」「怎么动」两类参数：
 *  - 外观参数给 FlyActor 用（颜色、体型、翅膀、腿、花纹）
 *  - 运动参数封在 FlyTraits 里给 FlyMotion 用
 *
 * 加一个新品种只需要在下面加一个条目，再放进 ALL 列表，不用改绘制和运动代码。
 */
class FlySpecies(
    /** 中文名，显示在主界面上 */
    val displayName: String,
    // ---- 结构 ----
    /** 有无触角 */
    val hasAntennae: Boolean,
    /** 触角样式：1 = 细长，2 = 羽状（蛾/蝶），3 = 短棒带芒（蝇类） */
    val antennaeStyle: Int,
    /**
     * 头型。真实的头不是一个球：
     *  蝇类 头部几乎全是复眼、呈半球紧贴前胸
     *  蜻蜓 复眼极大且左右相连，头几乎是个球形
     *  蚁/蟑螂 头是明显分离的一节，可以自由转动
     *  甲虫/蚊 头小而尖，缩在前胸下
     *  蛾/蝶/蟋蟀 头宽圆，覆有绒毛
     */
    val headStyle: HeadStyle,
    /**
     * 振动强度权重（0.35 很轻 ～ 1.35 很重）。
     * 决定这只虫子被戳中/落地时的振感 —— 蚂蚁不该和蟑螂一个手感。
     */
    val hapticWeight: Float,
    /** 身体分段数：1 = 整体，2 = 头胸腹分节，3 = 明显三节（蚂蚁/蟑螂） */
    val bodySegments: Int,
    /** 运动方式，决定运动分支和绘制姿态 */
    val locomotion: Locomotion,
    /** 翅膀形状 */
    val wingShape: WingShape,
    /**
     * 股节（腿最上面那节）相对腿宽的放大倍数。
     * 昆虫的腿是「粗股节 + 细胫节」，蟋蟀的跳跃股节尤其粗大。
     */
    val femurWidth: Float,
    /** 翅膀副色，用于蝶蛾的斑点花纹；0 表示无 */
    val wingAccent: Int,
    /** 是否有鞘翅（硬壳，不扇动） */
    val hasElytra: Boolean,
    // ---- 外观 ----
    val bodyCore: Int,
    val bodyLighter: Int,
    val bodyDarker: Int,
    val eyeMain: Int,
    val eyeHighlight: Int,
    /** 翅膀底色 */
    val wingTint: Int,
    /** 整体体型倍率（主界面滑块 1.0 时） */
    val sizeScale: Float,
    val legWidth: Float,
    /** 腿的粗细分级：1 = 中，2 = 粗短（甲虫/蟑螂），3 = 极细长（蚊/蚁/蜻蜓） */
    val legStyle: Int,
    /** 渲染细节等级：0 = 最小开销，1 = 中等，2 = 全细节。见 FlyActor 里的说明 */
    val detailLevel: Int,
    /** 背毛（蛾类） */
    val bristles: Boolean,
    /** 后腿特别粗大（蟋蟀） */
    val bigHindLegs: Boolean,
    // ---- 运动 ----
    val speedMul: Float,
    val dartMul: Float,
    /** 翅膀扇动频率（Hz）。鞘翅类不扇翅，取 0 */
    val flapHz: Float,
    /** 身体起伏频率 */
    val bobRate: Float,
    /** 转向平滑系数，越小越迟钝 */
    val turnRate: Float,
    /** 落停倾向 0..1，越大越爱趴着 */
    val landBias: Float,
    /** 悬停倾向 0..1，越大越会在空中停住不动 */
    val hoverTendency: Float = 0f,
) {
    fun traits(): FlyTraits = FlyTraits(
        speedMul = speedMul,
        dartMul = dartMul,
        flapHz = flapHz,
        landRate = 9f + sizeScale * 9f,
        flyBobRate = bobRate,
        turnRate = turnRate,
        landBias = landBias,
        hoverTendency = hoverTendency,
        locomotion = locomotion,
        detailLevel = detailLevel,
        headStyle = headStyle,
        femurWidth = femurWidth,
    )

    enum class HeadStyle {
        /** 蝇类：头几乎被复眼占满，紧贴前胸 */
        HEAD_FLY,
        /** 蜻蜓：复眼极大且左右相连，头呈球形 */
        HEAD_DOME,
        /** 蚂蚁/蟑螂：头是明显分离的一节，可自由转动 */
        HEAD_FREE,
        /** 甲虫/蚊：头小而尖，缩在前胸下 */
        HEAD_POINTY,
        /** 蛾/蝶/蟋蟀：头宽圆，覆有绒毛 */
        HEAD_BROAD,
        /** 臭虫/虱/衣鱼：头小而宽扁，紧贴前胸，从背面上看几乎被前胸盖住 */
        HEAD_FLAT,
    }

    enum class Locomotion {
        /** 苍蝇式：飘忽巡航 + 突然窜逃 + 偶尔落停爬行 */
        FLY,
        /** 甲虫式：贴地爬行，很少起飞 */
        CRAWL,
        /** 蟋蟀式：爬行 + 突然跳跃 */
        HOP,
        /** 蜻蜓/蝶式：巡航 + 空中悬停，很少落到地面 */
        HOVER,
    }

    enum class WingShape {
        /** 苍蝇的窄长翅 */
        FLY,
        /** 蛾子的宽圆翅 */
        MOTH,
        /** 蝴蝶的大翅，带斑点 */
        BUTTERFLY,
        /** 蜻蜓的四片细长翅 */
        DRAGONFLY,
        /** 蜜蜂/黄蜂的窄翅 */
        BEE,
        /** 甲虫的鞘翅（硬壳） */
        ELYTRA,
        /**
         * 臭虫/虱/衣鱼：**无翅**（或只剩退化翅芽），
         * 背面完全是分节的体节。这是它们最本质的特征 ——
         * 画上翅膀就全错了。
         */
        WINGLESS,
        /**
         * 跳蚤：**侧扁**（左右压扁，不是背腹扁平），所以俯视是一条
         * **3:1 的窄长条**，不是椭圆。而且背面有两排向后指的**刺梳**
         * （颊栉 + 前胸栉），这是它最好认的特征。
         */
        FLEA,
    }

    companion object {

        /**
         * 每个品种最多同时出现的只数。
         *
         * 数量上限 = min(品种数 × 这个值, [MAX_TOTAL])。
         * 想恢复"每種最多 2 只"就改回 2，并把数量上限三处同步降到 22。
         */
        const val MAX_PER_SPECIES = 5

        /** 实际放开的最大数量。11 种 × 5 = 55，这里取整成 50。 */
        const val MAX_TOTAL = 50

        // ============================================================ 蝇类

        /** 家蝇：最常见的那个 */
        val HOUSE = FlySpecies(
            displayName = "家蝇",
            hasAntennae = true, antennaeStyle = 3, headStyle = HeadStyle.HEAD_FLY, hapticWeight = 0.8f,
            bodySegments = 1, locomotion = Locomotion.FLY, wingShape = WingShape.FLY, femurWidth = 1.35f,
            wingAccent = 0, hasElytra = false,
            bodyCore = 0xFF1E2026.toInt(), bodyLighter = 0xFF4A4E56.toInt(),
            bodyDarker = 0xFF0E0F12.toInt(),
            eyeMain = 0xFF602C28.toInt(), eyeHighlight = 0xFFD29682.toInt(),
            wingTint = 0xFFE4EBF6.toInt(),
            sizeScale = 1.0f, legWidth = 1.45f, legStyle = 1, detailLevel = 2,
            bristles = true, bigHindLegs = false,
            speedMul = 1.0f, dartMul = 1.0f, flapHz = 34f, bobRate = 26f,
            turnRate = 6f, landBias = 0.30f,
        )

        /** 绿豆蝇：绿头、金属光泽，飞得更凶 */
        val BLOWFLY = FlySpecies(
            displayName = "绿豆蝇",
            hasAntennae = true, antennaeStyle = 3, headStyle = HeadStyle.HEAD_FLY, hapticWeight = 0.95f,
            bodySegments = 1, locomotion = Locomotion.FLY, wingShape = WingShape.FLY, femurWidth = 1.45f,
            wingAccent = 0, hasElytra = false,
            bodyCore = 0xFF1B3028.toInt(), bodyLighter = 0xFF3E8A63.toInt(),
            bodyDarker = 0xFF0D1A15.toInt(),
            eyeMain = 0xFF2F4A5C.toInt(), eyeHighlight = 0xFF9FD2C4.toInt(),
            wingTint = 0xFFD8EDE6.toInt(),
            sizeScale = 1.25f, legWidth = 1.6f, legStyle = 1, detailLevel = 2,
            bristles = true, bigHindLegs = false,
            speedMul = 1.15f, dartMul = 1.2f, flapHz = 30f, bobRate = 24f,
            turnRate = 5f, landBias = 0.18f,
        )

        /** 果蝇：小、黄褐、动作琐碎。体型太小，细节全砍 */
        val FRUITFLY = FlySpecies(
            displayName = "果蝇",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_FLY, hapticWeight = 0.35f,
            bodySegments = 1, locomotion = Locomotion.FLY, wingShape = WingShape.FLY, femurWidth = 1.15f,
            wingAccent = 0, hasElytra = false,
            bodyCore = 0xFF4A3220.toInt(), bodyLighter = 0xFFA9743C.toInt(),
            bodyDarker = 0xFF2A1B10.toInt(),
            eyeMain = 0xFF7A2E1C.toInt(), eyeHighlight = 0xFFE0A87A.toInt(),
            wingTint = 0xFFF2E4CF.toInt(),
            sizeScale = 0.62f, legWidth = 1.0f, legStyle = 1, detailLevel = 0,
            bristles = true, bigHindLegs = false,
            speedMul = 1.35f, dartMul = 1.5f, flapHz = 42f, bobRate = 34f,
            turnRate = 9f, landBias = 0.10f,
        )

        /** 蚊子：细长腿、高频扇翅 */
        val MOSQUITO = FlySpecies(
            displayName = "蚊子",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_POINTY, hapticWeight = 0.45f,
            bodySegments = 2, locomotion = Locomotion.FLY, wingShape = WingShape.FLY, femurWidth = 1.20f,
            wingAccent = 0, hasElytra = false,
            bodyCore = 0xFF2C2A26.toInt(), bodyLighter = 0xFF6E6558.toInt(),
            bodyDarker = 0xFF14130F.toInt(),
            eyeMain = 0xFF3A3226.toInt(), eyeHighlight = 0xFFC8B48C.toInt(),
            wingTint = 0xFFE8E8DE.toInt(),
            sizeScale = 0.82f, legWidth = 0.85f, legStyle = 3, detailLevel = 1,
            bristles = false, bigHindLegs = false,
            speedMul = 1.05f, dartMul = 1.3f, flapHz = 52f, bobRate = 30f,
            turnRate = 8f, landBias = 0.14f, hoverTendency = 0.25f,
        )

        // ============================================================ 蛾蝶类

        /** 蛾子：肥、毛、翅膀宽圆、飞得慢而飘 */
        val MOTH = FlySpecies(
            displayName = "蛾子",
            hasAntennae = true, antennaeStyle = 2, headStyle = HeadStyle.HEAD_BROAD, hapticWeight = 1.15f,
            bodySegments = 3, locomotion = Locomotion.HOVER, wingShape = WingShape.MOTH, femurWidth = 1.40f,
            wingAccent = 0x66A89478, hasElytra = false,
            bodyCore = 0xFF4E4133.toInt(), bodyLighter = 0xFF9C8768.toInt(),
            bodyDarker = 0xFF2C241B.toInt(),
            eyeMain = 0xFF241F18.toInt(), eyeHighlight = 0xFFB9A582.toInt(),
            wingTint = 0xFFF0E2C6.toInt(),
            sizeScale = 2.05f, legWidth = 1.9f, legStyle = 2, detailLevel = 2,
            bristles = true, bigHindLegs = false,
            speedMul = 0.55f, dartMul = 0.7f, flapHz = 13f, bobRate = 12f,
            turnRate = 2.6f, landBias = 0.45f, hoverTendency = 0.30f,
        )

        /** 蝴蝶：比蛾子更鲜艳、更爱停、翅上有斑点 */
        val BUTTERFLY = FlySpecies(
            displayName = "蝴蝶",
            hasAntennae = true, antennaeStyle = 2, headStyle = HeadStyle.HEAD_BROAD, hapticWeight = 1.05f,
            bodySegments = 3, locomotion = Locomotion.HOVER, wingShape = WingShape.BUTTERFLY, femurWidth = 1.25f,
            wingAccent = 0xFF2E2A22.toInt(), hasElytra = false,
            bodyCore = 0xFF241F1A.toInt(), bodyLighter = 0xFF6B5A42.toInt(),
            bodyDarker = 0xFF14110D.toInt(),
            eyeMain = 0xFF1A1611.toInt(), eyeHighlight = 0xFFA08B66.toInt(),
            wingTint = 0xFFF6C445.toInt(),
            sizeScale = 1.95f, legWidth = 1.2f, legStyle = 1, detailLevel = 2,
            bristles = true, bigHindLegs = false,
            speedMul = 0.75f, dartMul = 0.9f, flapHz = 9f, bobRate = 10f,
            turnRate = 3.2f, landBias = 0.52f, hoverTendency = 0.22f,
        )

        // ============================================================ 直翅类

        /** 蟋蟀：粗后腿、会跳 */
        val CRICKET = FlySpecies(
            displayName = "蟋蟀",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_BROAD, hapticWeight = 1.05f,
            bodySegments = 3, locomotion = Locomotion.HOP, wingShape = WingShape.ELYTRA, femurWidth = 1.90f,
            wingAccent = 0, hasElytra = true,
            bodyCore = 0xFF2E2A1D.toInt(), bodyLighter = 0xFF6E6238.toInt(),
            bodyDarker = 0xFF19160E.toInt(),
            eyeMain = 0xFF231F14.toInt(), eyeHighlight = 0xFF9C8B5A.toInt(),
            wingTint = 0xFF4A4530.toInt(),
            sizeScale = 1.35f, legWidth = 2.2f, legStyle = 2, detailLevel = 2,
            bristles = false, bigHindLegs = true,
            speedMul = 0.45f, dartMul = 2.4f, flapHz = 0f, bobRate = 16f,
            turnRate = 4f, landBias = 0.72f,
        )

        // ============================================================ 鞘翅类

        /** 蟑螂：扁平、油亮、跑得极快、几乎不飞 */
        val COCKROACH = FlySpecies(
            displayName = "蟑螂",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_FREE, hapticWeight = 1.35f,
            bodySegments = 3, locomotion = Locomotion.CRAWL, wingShape = WingShape.ELYTRA, femurWidth = 1.30f,
            wingAccent = 0, hasElytra = true,
            bodyCore = 0xFF3A2016.toInt(), bodyLighter = 0xFF8A5230.toInt(),
            bodyDarker = 0xFF1C0F09.toInt(),
            eyeMain = 0xFF241209.toInt(), eyeHighlight = 0xFFA9703F.toInt(),
            wingTint = 0xFF4A2A1A.toInt(),
            sizeScale = 1.7f, legWidth = 1.3f, legStyle = 1, detailLevel = 2,
            bristles = false, bigHindLegs = false,
            speedMul = 1.6f, dartMul = 1.8f, flapHz = 0f, bobRate = 20f,
            turnRate = 7f, landBias = 0.93f,
        )

        /**
         * 瓢虫 Coccinella septempunctata。按真实背面数据校正：
         *
         *  - 体形是**椭圆，不是圆**：宽 : 长 = 0.77（5.20–8.60 × 4.00–6.60 mm，
         *    原始来源 Poorani 2023 的分类学处理）。"Form oval, strongly convex."
         *  - 斑点固定为 **7 枚 = 每鞘翅 3 枚 + 1 枚盾片斑**（跨中缝，倒心形）。
         *    我原来是随手摆了 3 个点，数量位置都不对。
         *  - 前胸**底色黑 + 两枚白斑**（前侧角），不是纯黑。
         *  - 头部黑，两复眼内侧各有一枚**白色半月形额斑**。
         *  - 中缝（suture）是一条贯穿鞘翅全长的细黑线，且盾片处有个小三角（盾片）。
         */
        val LADYBUG = FlySpecies(
            displayName = "瓢虫",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_POINTY, hapticWeight = 0.6f,
            bodySegments = 1, locomotion = Locomotion.CRAWL, wingShape = WingShape.ELYTRA, femurWidth = 1.20f,
            wingAccent = 0xFF14100E.toInt(), hasElytra = true,
            bodyCore = 0xFFD8361F.toInt(), bodyLighter = 0xFFE4641E.toInt(),
            bodyDarker = 0xFF7A1A0C.toInt(),
            eyeMain = 0xFF14100E.toInt(), eyeHighlight = 0xFF8A8A8A.toInt(),
            wingTint = 0xFFD8361F.toInt(),
            sizeScale = 1.0f, legWidth = 1.0f, legStyle = 2, detailLevel = 2,
            bristles = false, bigHindLegs = false,
            speedMul = 0.75f, dartMul = 0.9f, flapHz = 26f, bobRate = 18f,
            turnRate = 4f, landBias = 0.55f,
        )

        // ============================================================ 膜翅类

        /**
         * 蜜蜂 Apis mellifera（工蜂）。按真实背面解剖校正：
         *
         *  - 腹部底色是**深褐/黑**，黄带在**腹节前缘**（terga 2~4 共 3 条）。
         *    是"黄带压在深色底上"，不是"黑纹压在黄底上"—— 我原来画反了。
         *  - **胸部是金褐色绒毛团**，明显比腹部浅、更蓬松，这是最好认的特征。
         *  - 蜜蜂腰**不细**（细腰是胡蜂的特征，这正是区分二者的要点），
         *    胸腹之间只能轻微收窄。
         *  - 触角**肘状**：柄节向前上、再急转折向前下。
         *  - 后足胫节宽扁、外侧面光滑发亮、边缘一圈硬毛 = 花粉篮（工蜂独有）。
         */
        val BEE = FlySpecies(
            displayName = "蜜蜂",
            hasAntennae = true, antennaeStyle = 4, headStyle = HeadStyle.HEAD_FLY, hapticWeight = 0.9f,
            bodySegments = 2, locomotion = Locomotion.HOVER, wingShape = WingShape.BEE, femurWidth = 1.45f,
            wingAccent = 0xFF2A2118.toInt(), hasElytra = false,
            // 腹部深褐（黄带由 drawBeeStripes 画在腹节前缘）
            bodyCore = 0xFF3A2A1C.toInt(), bodyLighter = 0xFF6B4423.toInt(),
            bodyDarker = 0xFF1C1410.toInt(),
            eyeMain = 0xFF241C16.toInt(), eyeHighlight = 0xFF9A8352.toInt(),
            wingTint = 0xFFE9F1F4.toInt(),
            sizeScale = 1.15f, legWidth = 1.5f, legStyle = 1, detailLevel = 1,
            bristles = true, bigHindLegs = false,
            speedMul = 0.95f, dartMul = 1.0f, flapHz = 55f, bobRate = 22f,
            turnRate = 5f, landBias = 0.35f, hoverTendency = 0.35f,
        )

        /** 蚂蚁：很小、跑得快、走直线。体型太小，细节全砍 */
        val ANT = FlySpecies(
            displayName = "蚂蚁",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_FREE, hapticWeight = 0.4f,
            bodySegments = 3, locomotion = Locomotion.CRAWL, wingShape = WingShape.ELYTRA, femurWidth = 1.35f,
            wingAccent = 0, hasElytra = true,
            bodyCore = 0xFF2A1A12.toInt(), bodyLighter = 0xFF7A4A2E.toInt(),
            bodyDarker = 0xFF140C08.toInt(),
            eyeMain = 0xFF1A1108.toInt(), eyeHighlight = 0xFF8A6A44.toInt(),
            wingTint = 0xFF2A1A12.toInt(),
            sizeScale = 0.7f, legWidth = 0.95f, legStyle = 3, detailLevel = 0,
            bristles = false, bigHindLegs = false,
            speedMul = 1.45f, dartMul = 1.6f, flapHz = 0f, bobRate = 30f,
            turnRate = 10f, landBias = 0.88f,
        )

        // ============================================================ 蜻蜓类

        /** 蜻蜓：四片长翅、悬停高手、腿极细 */
        val DRAGONFLY = FlySpecies(
            displayName = "蜻蜓",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_DOME, hapticWeight = 1.25f,
            bodySegments = 2, locomotion = Locomotion.HOVER, wingShape = WingShape.DRAGONFLY, femurWidth = 1.25f,
            wingAccent = 0xFF3E5A54.toInt(), hasElytra = false,
            bodyCore = 0xFF1F4A46.toInt(), bodyLighter = 0xFF4FA89A.toInt(),
            bodyDarker = 0xFF0D2422.toInt(),
            eyeMain = 0xFF3A7A6E.toInt(), eyeHighlight = 0xFFB8F0E4.toInt(),
            wingTint = 0xFFE0F2F0.toInt(),
            sizeScale = 1.8f, legWidth = 0.8f, legStyle = 3, detailLevel = 2,
            bristles = false, bigHindLegs = false,
            speedMul = 1.3f, dartMul = 1.4f, flapHz = 28f, bobRate = 14f,
            turnRate = 6.5f, landBias = 0.12f, hoverTendency = 0.55f,
        )

        // ============================================================ 无翅吸血类

        /**
         * 臭虫（床虱）Cimex lectularius。
         *
         * 背面形态的关键点（和所有蝇类完全不同）：
         *  - **无翅**，背面是分节的腹部
         *  - 身体扁平呈宽椭圆，比苍蝇圆胖得多
         *  - 前胸背板向两侧扩张（像两个小翅膀），是最好认的特征
         *  - 腹部各节后缘呈波浪状，两侧有半透明的浅色边缘
         *  - 红褐色，中央深、边缘浅
         *  - 头短而宽，从背面上看大部分被前胸盖住
         */
        val BEDBUG = FlySpecies(
            displayName = "臭虫",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_FLAT, hapticWeight = 0.55f,
            bodySegments = 3, locomotion = Locomotion.CRAWL, wingShape = WingShape.WINGLESS,
            femurWidth = 1.15f,
            wingAccent = 0, hasElytra = false,
            bodyCore = 0xFF7A3320.toInt(), bodyLighter = 0xFFB0603A.toInt(),
            bodyDarker = 0xFF3E180E.toInt(),
            eyeMain = 0xFF2A1409.toInt(), eyeHighlight = 0xFF9A6A44.toInt(),
            wingTint = 0xFF8A4028.toInt(),
            sizeScale = 0.95f, legWidth = 0.9f, legStyle = 1, detailLevel = 1,
            bristles = false, bigHindLegs = false,
            speedMul = 0.85f, dartMul = 1.0f, flapHz = 0f, bobRate = 16f,
            turnRate = 5f, landBias = 0.90f,
        )

        /** 头虱 Pediculus humanus capitis：细长、分节明显、腹部有深色横带、爪状足。 */
        val LOUSE = FlySpecies(
            displayName = "虱子",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_FLAT, hapticWeight = 0.35f,
            bodySegments = 3, locomotion = Locomotion.CRAWL, wingShape = WingShape.WINGLESS,
            femurWidth = 1.25f,
            wingAccent = 0, hasElytra = false,
            bodyCore = 0xFF6B5544.toInt(), bodyLighter = 0xFF9C8874.toInt(),
            bodyDarker = 0xFF3A2C22.toInt(),
            eyeMain = 0xFF241A12.toInt(), eyeHighlight = 0xFF8A7460.toInt(),
            wingTint = 0xFF6B5544.toInt(),
            sizeScale = 0.6f, legWidth = 1.05f, legStyle = 2, detailLevel = 1,
            bristles = false, bigHindLegs = false,
            speedMul = 0.7f, dartMul = 0.8f, flapHz = 0f, bobRate = 22f,
            turnRate = 4f, landBias = 0.94f,
        )

        /**
         * 衣鱼 Lepisma saccharinum。
         *
         * 它的形状是所有品种里最特别的：
         *  - **泪滴形**：头宽、向后逐渐收窄成一个尖尾
         *  - 无翅，但体表有**银色鳞片**，反光很强
         *  - 尾部三根长尾须（两根尾须 + 一根中尾丝），一眼就能认出来
         *  - 触角极长，几乎和身体一样长
         */
        val SILVERFISH = FlySpecies(
            displayName = "衣鱼",
            hasAntennae = true, antennaeStyle = 1, headStyle = HeadStyle.HEAD_FLAT, hapticWeight = 0.5f,
            bodySegments = 3, locomotion = Locomotion.CRAWL, wingShape = WingShape.WINGLESS,
            femurWidth = 1.1f,
            wingAccent = 0, hasElytra = false,
            bodyCore = 0xFF9AA0A6.toInt(), bodyLighter = 0xFFD8DEE4.toInt(),
            bodyDarker = 0xFF5A6068.toInt(),
            eyeMain = 0xFF2A2E33.toInt(), eyeHighlight = 0xFFB0B8C0.toInt(),
            wingTint = 0xFF9AA0A6.toInt(),
            sizeScale = 1.05f, legWidth = 0.85f, legStyle = 1, detailLevel = 1,
            bristles = false, bigHindLegs = false,
            speedMul = 1.1f, dartMul = 1.3f, flapHz = 0f, bobRate = 24f,
            turnRate = 7f, landBias = 0.88f,
        )

        /**
         * 跳蚤（猫栉首蚤）Ctenocephalides felis。
         *
         * 背面形态（来源见 README）：
         *  - **侧扁**：2~3mm 长 × 0.6~1.0mm 宽，俯视长宽比约 **3:1**，
         *    轮廓是一条窄长条，**不是椭圆**。
         *  - 头**三角形**，额头平斜（这是和狗蚤区分的关键）。
         *  - **颊栉**：眼下方一排 8~9 枚扁刺，像"胡子"。
         *  - **前胸栉**：头胸交界处一排横刺，像"项圈"。
         *    这两排刺全部**向后指**，是俯视时最好认的特征。
         *  - 眼是**黑色圆形单眼**，位于触角窝前方（触角藏在窝里，背面看不见）。
         *  - 腹部 7 节，每节背板后缘有一排向后指的鬃毛；
         *    末端有个圆形的**臀板**（感觉器）。
         */
        val FLEA = FlySpecies(
            displayName = "跳蚤",
            hasAntennae = false, antennaeStyle = 0, headStyle = HeadStyle.HEAD_POINTY, hapticWeight = 0.45f,
            bodySegments = 3, locomotion = Locomotion.HOP, wingShape = WingShape.FLEA, femurWidth = 1.5f,
            wingAccent = 0xFF3A2415.toInt(), hasElytra = false,
            bodyCore = 0xFF74462A.toInt(), bodyLighter = 0xFFA9713F.toInt(),
            bodyDarker = 0xFF422714.toInt(),
            eyeMain = 0xFF101010.toInt(), eyeHighlight = 0xFF6A6A6A.toInt(),
            wingTint = 0xFF74462A.toInt(),
            sizeScale = 0.75f, legWidth = 1.1f, legStyle = 1, detailLevel = 1,
            bristles = false, bigHindLegs = true,
            speedMul = 0.5f, dartMul = 3.0f, flapHz = 0f, bobRate = 18f,
            turnRate = 4f, landBias = 0.70f,
        )

        /** 全部品种，按加入顺序。数量分配见 allocate()。 */
        val ALL: List<FlySpecies> = listOf(
            HOUSE, BLOWFLY, FRUITFLY, MOTH, MOSQUITO, COCKROACH,
            BEE, LADYBUG, CRICKET, BUTTERFLY, ANT, DRAGONFLY,
            BEDBUG, LOUSE, SILVERFISH, FLEA,
        )

        fun byIndex(i: Int): FlySpecies =
            ALL[((i % ALL.size) + ALL.size) % ALL.size]

        fun count(): Int = ALL.size

        /** 最多能放出多少只 = min(品种数 × 每種上限, MAX_TOTAL)。 */
        fun maxTotal(): Int = minOf(ALL.size * MAX_PER_SPECIES, MAX_TOTAL)

        /**
         * 按「每个品种最多 MAX_PER_SPECIES 只」的原则分配品种。
         *
         * 用轮转法而不是"把一个品种填满再换下一个"：
         * 轮转既满足每種上限，又能让品种尽可能分散。
         *   11 种、要放 5 只  -> 前 5 个品种各 1 只
         *   11 种、要放 22 只 -> 11 个品种各 2 只
         *   11 种、要放 50 只 -> 循环 5 轮，前 6 个品种各 3 只、后 5 个各 2 只
         *
         * FlyLayerView（真正分配）和 MainActivity（界面预览）共用这个函数，
         * 保证预览和实际放出的一定一致。
         */
        fun allocate(count: Int): List<FlySpecies> {
            if (count <= 0) return emptyList()
            val n = count.coerceAtMost(maxTotal())
            val result = ArrayList<FlySpecies>(n)

            // 每个品种需要放几只 = ceil(n / 品种数)，但不能超过每種上限。
            // 注意不要用"循环轮次"当上限：轮次是外层计数，内层每轮都会跑遍所有品种，
            // 直接拿 round 和 MAX_PER_SPECIES 比会把总数错误地卡在 品种数 × 2。
            val rounds = ((n + ALL.size - 1) / ALL.size).coerceAtMost(MAX_PER_SPECIES)

            var r = 0
            while (r < rounds && result.size < n) {
                for (sp in ALL) {
                    if (result.size >= n) break
                    result.add(sp)
                }
                r++
            }
            return result
        }

        /** 界面预览用的品种名单（含重名，按实际放出顺序）。 */
        fun displayNames(count: Int): List<String> =
            allocate(count).map { it.displayName }
    }
}

/**
 * 某个品种的运动参数。
 *
 * 单独抽出来是因为 FlyMotion 只关心怎么动、FlyActor 只关心长什么样，
 * 这样运动逻辑和绘制逻辑彻底解耦。
 */
class FlyTraits(
    val speedMul: Float,
    val dartMul: Float,
    val flapHz: Float,
    /** 停落/爬行时身体起伏频率 */
    val landRate: Float,
    /** 飞行时身体起伏频率 */
    val flyBobRate: Float,
    val turnRate: Float,
    val landBias: Float,
    /** 悬停倾向 0..1 */
    val hoverTendency: Float = 0f,
    /** 运动方式，FlyMotion 按它走不同分支 */
    val locomotion: FlySpecies.Locomotion = FlySpecies.Locomotion.FLY,
    /** 渲染细节等级，FlyActor 按它砍开销 */
    val detailLevel: Int = 2,
    /** 头型，FlyActor 按它画不同形状的头 */
    val headStyle: FlySpecies.HeadStyle = FlySpecies.HeadStyle.HEAD_FLY,
    /** 股节粗细倍数 */
    val femurWidth: Float = 1.35f,
) {
    companion object {
        /** 默认值 = 家蝇 */
        val DEFAULT = FlyTraits(
            speedMul = 1f, dartMul = 1f, flapHz = 34f,
            landRate = 26f, flyBobRate = 26f, turnRate = 6f,
            landBias = 0.30f, hoverTendency = 0f,
            locomotion = FlySpecies.Locomotion.FLY,
            detailLevel = 2,
            headStyle = FlySpecies.HeadStyle.HEAD_FLY,
            femurWidth = 1.35f,
        )
    }
}
