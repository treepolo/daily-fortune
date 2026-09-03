package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.AstroAspect
import com.treepolo.dailyfortune.model.AstroBody
import com.treepolo.dailyfortune.model.FortuneDomain

/**
 * Final production weights derived from Astrology Weight Search v4 expanded run #3, scheme 1.
 * The engine consumes these as direct factor affinities; there is no post-hoc factor multiplier layer.
 */
object AstrologyWeightsV12 {
    private val baseBodyAffinity = mapOf(
        AstroBody.SUN to values(.25, .20, .75, .25, .85),
        AstroBody.MOON to values(.20, .70, .20, .80, .75),
        AstroBody.MERCURY to values(.55, .35, 1.00, .70, .20),
        AstroBody.VENUS to values(.55, 1.00, .25, .95, .30),
        AstroBody.MARS to values(.35, .65, .85, .55, .85),
        AstroBody.JUPITER to values(.95, .45, .80, .60, .50),
        AstroBody.SATURN to values(.75, .35, .90, .45, .70),
        AstroBody.URANUS to values(.45, .45, .55, .55, .35),
        AstroBody.NEPTUNE to values(.35, .65, .45, .55, .50),
        AstroBody.PLUTO to values(.50, .55, .55, .55, .45),
    )

    private val bodyFactorWeight = mapOf(
        AstroBody.SUN to values(0.51, 0.51, 0.51, 2.4947068374668913, 1.6999539412162237),
        AstroBody.MOON to values(0.51, 0.51, 0.51, 0.980696998920567, 4.27618753164621),
        AstroBody.MERCURY to values(0.51, 0.51, 0.51, 0.6434907232000194, 0.7621881330894),
        AstroBody.VENUS to values(0.51, 0.51, 0.53, 0.51, 5.0),
        AstroBody.MARS to values(0.51, 0.51, 0.51, 2.4111657642116313, 5.0),
        AstroBody.JUPITER to values(0.51, 0.542285390000788, 0.51, 0.51, 0.9094967488489121),
        AstroBody.SATURN to values(0.51, 0.51, 0.5217122621987923, 1.8828778700425755, 2.5720658451861893),
        AstroBody.URANUS to values(0.51, 0.51, 0.51, 0.5589023667372353, 1.3085964778700374),
        AstroBody.NEPTUNE to values(0.51, 0.52, 0.51, 0.8835605935988553, 0.919518917587199),
        AstroBody.PLUTO to values(0.51, 0.51, 0.51, 0.708592917608228, 1.9454253226166283),
    )

    private val houseFactorShift = mapOf(
        1 to values(0.09738817218040904, 0.10438434369573962, -0.49, 0.1722310960128431, 3.3687856094398554),
        2 to values(0.04732439350017288, 1.3640675836916227, -0.09227219589284655, 0.41577274992555596, 2.355450436187522),
        3 to values(0.10584178664417757, 2.5006588344541023, 1.2974668537154557, -0.4445982256298708, 0.4853796099639558),
        4 to values(0.4600825882783463, 3.952474950197802, -0.060924647374250455, 0.5661368271590519, 3.96464872212791),
        5 to values(1.7538536860573992, 3.879845615959657, -0.49, 3.5088180180098405, 0.781689313699363),
        6 to values(-0.4080344075928916, -0.16540695881831574, 0.7355963247899405, 1.8065809641339035, 3.716640669953562),
        7 to values(0.4748953530154907, 2.8018348345398625, 2.090823961580444, -0.25188091695514647, 0.26668457655626054),
        8 to values(-0.2320663707696265, 3.6550439511033272, 0.7399948303726827, 0.0006833510243929516, -0.22865679453223103),
        9 to values(0.7495384010593853, 1.2844922461020216, 1.710164169490443, 0.005982092612865135, 1.0194271345679577),
        10 to values(4.0, 1.4879096050108702, 4.0, 0.6537877336617459, 3.179098809485791),
        11 to values(1.03508551602049, 1.9138180077716729, -0.49, -0.49, -0.13413650751385672),
        12 to values(1.0972775591704513, 2.4504658124121548, 3.9863339895471572, 0.10204728992088452, 2.4945146384535035),
    )

    private val aspectFactorShift = mapOf(
        AstroAspect.CONJUNCTION to values(-0.49, -0.4402088883988888, -0.49, -0.08285351420200053, 0.13615800188717392),
        AstroAspect.SEXTILE to values(-0.3404620466121937, -0.49, -0.49, 0.8277297856507762, 2.8874844235873485),
        AstroAspect.SQUARE to values(-0.49, -0.49, -0.49, 3.7463143247741866, 0.731807795658827),
        AstroAspect.TRINE to values(-0.49, -0.49, -0.49, -0.4839654818352665, 0.0755769767468027),
        AstroAspect.OPPOSITION to values(-0.49, -0.4796692233263943, -0.4827433513983344, -0.4624583177309005, 4.0),
    )

    private val houseRelevance = mapOf(
        1 to values(0.0, 0.0, .25, .20, 1.00),
        2 to values(1.00, 0.0, 0.0, 0.0, 0.0),
        3 to values(0.0, 0.0, .90, .50, 0.0),
        4 to values(0.0, .45, 0.0, .55, .35),
        5 to values(0.0, 1.00, .35, .25, 0.0),
        6 to values(0.0, 0.0, .85, 0.0, 1.00),
        7 to values(.25, 1.00, 0.0, 1.00, 0.0),
        8 to values(.75, .45, 0.0, 0.0, .35),
        9 to values(0.0, 0.0, .85, .25, 0.0),
        10 to values(.40, 0.0, 1.00, .20, 0.0),
        11 to values(.35, .25, .35, 1.00, 0.0),
        12 to values(0.0, .25, .20, .25, .60),
    )

    val domainGradeThresholds = mapOf(
        FortuneDomain.WEALTH to doubleArrayOf(-2.6958693347743794, -0.7891658490567275, 0.559749351357153, 1.8369966331193541, 3.5263472734595585, 6.705888849141977),
        FortuneDomain.LOVE to doubleArrayOf(-4.9034592643870845, -0.9712506003799356, 2.4604378068588537, 6.174031239650987, 11.924375679937432, 19.193948885625634),
        FortuneDomain.WORK_STUDY to doubleArrayOf(-11.343915741285572, -4.776617178762415, -1.0634062197761625, 2.103397480245748, 6.370229755115553, 13.055471797100873),
        FortuneDomain.RELATIONSHIPS to doubleArrayOf(-41.26169409571241, -27.202622633216063, -17.545410859875393, -9.691399532809182, -1.8669574181941215, 4.985062171435285),
        FortuneDomain.HEALTH to doubleArrayOf(-51.88102994960997, -31.240914435589918, -14.904592746207063, -1.2734250247423522, 12.98518127381329, 28.511420760760743),
    )

    val overallGradeThresholds = doubleArrayOf(
        -15.958460591679342,
        -10.33723444757988,
        -5.228616858268023,
        -0.8107079713325007,
        3.6728116233269645,
        7.940461609302975,
    )

    fun bodyFactorAffinity(body: AstroBody, domain: FortuneDomain): Double =
        baseBodyAffinity.getValue(body).getValue(domain) * bodyFactorWeight.getValue(body).getValue(domain)

    fun houseFactorAffinity(body: AstroBody, house: Int, domain: FortuneDomain): Double =
        baseBodyAffinity.getValue(body).getValue(domain) * houseRelevance.getValue(house).getValue(domain) *
            (bodyFactorWeight.getValue(body).getValue(domain) + houseFactorShift.getValue(house).getValue(domain))

    fun aspectFactorAffinity(
        first: AstroBody,
        second: AstroBody,
        aspect: AstroAspect,
        domain: FortuneDomain,
    ): Double {
        val baseAffinity = (baseBodyAffinity.getValue(first).getValue(domain) + baseBodyAffinity.getValue(second).getValue(domain)) / 2.0
        val calibratedWeight = (bodyFactorWeight.getValue(first).getValue(domain) + bodyFactorWeight.getValue(second).getValue(domain)) / 2.0 +
            aspectFactorShift.getValue(aspect).getValue(domain)
        return baseAffinity * calibratedWeight
    }

    fun houseRelevance(house: Int, domain: FortuneDomain): Double =
        houseRelevance[house]?.get(domain) ?: 0.0

    private fun values(
        wealth: Double,
        love: Double,
        workStudy: Double,
        relationships: Double,
        health: Double,
    ) = mapOf(
        FortuneDomain.WEALTH to wealth,
        FortuneDomain.LOVE to love,
        FortuneDomain.WORK_STUDY to workStudy,
        FortuneDomain.RELATIONSHIPS to relationships,
        FortuneDomain.HEALTH to health,
    )
}
