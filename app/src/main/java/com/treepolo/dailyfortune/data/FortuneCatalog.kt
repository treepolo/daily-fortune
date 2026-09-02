package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.DomainFortune
import com.treepolo.dailyfortune.model.FortuneDefinition
import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.FortuneGrade

/**
 * 開發期私人改命測試籤池。
 *
 * 籤詩本體均取自公有領域《關聖帝君靈籤》，目前只放少量已人工核對的籤來驗證產品機制。
 * 正式版本會以結構化資料匯入完整 100 籤，不加入自創籤詩。
 * 抽取一律由 DailyDestinyProvider 的 SecureRandom 路徑執行；Catalog 本身不提供隨機 API。
 */
object FortuneCatalog {
    val fortunes: List<FortuneDefinition> = listOf(
        FortuneDefinition(
            number = 1,
            sourceGrade = "大吉",
            grade = FortuneGrade.DAI_JI,
            poem = listOf(
                "巍巍獨步向雲間",
                "玉殿千官第一班",
                "富貴榮華天付汝",
                "福如東海壽如山",
            ),
            generalExplanation = "整體局勢偏順，適合把握已經成熟的機會；原籤雖然吉意很強，仍提醒使用者不要把好運理解成可以忽略判斷與風險。",
            domains = mapOf(
                FortuneDomain.WEALTH to DomainFortune(FortuneGrade.JI, "財務條件偏有利，既有投入或人脈可能帶來回報；涉及高風險決策時仍應依實際資訊判斷。"),
                FortuneDomain.LOVE to DomainFortune(FortuneGrade.DAI_JI, "關係發展與建立連結的條件良好，適合把重要的話說清楚，也適合推進已有共識的關係。"),
                FortuneDomain.WORK_STUDY to DomainFortune(FortuneGrade.DAI_JI, "工作或學習容易取得進展，尤其適合處理已準備一段時間、現在可以正式推出或驗收的事情。"),
                FortuneDomain.RELATIONSHIPS to DomainFortune(FortuneGrade.JI, "容易得到他人支持，也適合主動聯絡能互相幫忙的人；仍要避免因順利而忽視他人的界線。"),
                FortuneDomain.HEALTH to DomainFortune(FortuneGrade.JI, "整體狀態偏穩，適合維持正常作息與既有照護；籤運不能取代醫療判斷。"),
            ),
        ),
        FortuneDefinition(
            number = 29,
            sourceGrade = "上上",
            grade = FortuneGrade.DAI_JI,
            poem = listOf(
                "祖宗積德幾多年",
                "源遠流長慶自然",
                "若更操修無倦已",
                "天須還汝舊青氈",
            ),
            generalExplanation = "這支籤把好結果連到長期累積與持續修為，重點在既有基礎開始回報；越是順利，越適合延續原本有效的做法。",
            domains = mapOf(
                FortuneDomain.WEALTH to DomainFortune(FortuneGrade.DAI_JI, "財運偏向長期累積開始兌現，熟悉的合作、技能或既有資源比臨時投機更值得依靠。"),
                FortuneDomain.LOVE to DomainFortune(FortuneGrade.DAI_JI, "感情條件偏穩，已建立的信任容易繼續發展；單身者則較適合從熟悉的社交圈與長期互動中觀察機會。"),
                FortuneDomain.WORK_STUDY to DomainFortune(FortuneGrade.DAI_JI, "過去累積的能力或成果容易在今天派上用場，適合完成、提交、爭取或延續已經有基礎的計畫。"),
                FortuneDomain.RELATIONSHIPS to DomainFortune(FortuneGrade.JI, "舊有關係與長期信用可能帶來協助，維持可靠與互惠比擴張大量淺層連結更有利。"),
                FortuneDomain.HEALTH to DomainFortune(FortuneGrade.JI, "狀態偏穩，持續既有健康習慣比突然採取激進改變更合適；有症狀時仍應依專業醫療處理。"),
            ),
        ),
        FortuneDefinition(
            number = 87,
            sourceGrade = "下下",
            grade = FortuneGrade.DAI_XIONG,
            poem = listOf(
                "陰裏詳看怪爾曹",
                "舟中敵圖笑中刀",
                "藩籬剖破渾無事",
                "一種天生惜羽毛",
            ),
            generalExplanation = "這支籤的核心風險在人際猜忌、暗中衝突與錯判他人意圖。今天遇到資訊不完整的事情時，先查證、降速並避免把猜測當成事實。",
            domains = mapOf(
                FortuneDomain.WEALTH to DomainFortune(FortuneGrade.DAI_XIONG, "財務上避免因熟人關係、口頭承諾或資訊不透明而放鬆查核，今天尤其不適合用信任取代風險控制。"),
                FortuneDomain.LOVE to DomainFortune(FortuneGrade.XIONG, "容易因猜測、隱瞞或彼此防備造成摩擦，重要問題應直接確認，不要靠試探與腦補推進。"),
                FortuneDomain.WORK_STUDY to DomainFortune(FortuneGrade.XIAO_XIONG, "合作與資訊交接可能出現落差，提交成果前多做一次核對，對不清楚的責任與條件留下文字紀錄。"),
                FortuneDomain.RELATIONSHIPS to DomainFortune(FortuneGrade.DAI_XIONG, "今天最需要注意的是人際衝突與互不信任；遇到傳話、流言或模糊指控時，先處理可驗證的事實。"),
                FortuneDomain.HEALTH to DomainFortune(FortuneGrade.XIAO_XIONG, "壓力與警戒感可能讓狀態變差，先處理休息與已知問題；若有實際症狀，應依醫療專業判斷而非籤運。"),
            ),
        ),
    )

    fun byNumber(number: Int?): FortuneDefinition? =
        fortunes.firstOrNull { it.number == number }
}
