package com.treepolo.dailyfortune.data

import com.treepolo.dailyfortune.model.FortuneDomain
import com.treepolo.dailyfortune.model.ZodiacSign
import java.io.File
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emits a deterministic fixture that the Deno port verifies in the same CI run. */
class AstrologyParityFixtureTest {
    @Test
    fun emitKotlinParityFixture() {
        val date = LocalDate.of(2026, 9, 2)
        val destinies = AstrologyEngine.calculateDay(date)
        val workspace = System.getenv("GITHUB_WORKSPACE")?.let(::File) ?: File(".")
        val output = File(workspace, "app/build/astrology-kotlin-fixture.json")
        output.parentFile?.mkdirs()

        output.writeText(buildString {
            append("{\"date\":\"")
            append(date)
            append("\",\"signs\":{")
            ZodiacSign.entries.forEachIndexed { signIndex, sign ->
                if (signIndex > 0) append(',')
                val destiny = destinies.getValue(sign)
                append("\"").append(sign.name).append("\":{")
                append("\"overallScore\":").append(number(destiny.audit.overallScore)).append(',')
                append("\"overallGrade\":\"").append(destiny.overallGrade.name).append("\",")
                append("\"domains\":{")
                FortuneDomain.entries.forEachIndexed { domainIndex, domain ->
                    if (domainIndex > 0) append(',')
                    append("\"").append(domain.name).append("\":{")
                    append("\"score\":").append(number(destiny.audit.domainScores.getValue(domain))).append(',')
                    append("\"grade\":\"").append(destiny.domains.getValue(domain).grade.name).append("\"}")
                }
                append("}}")
            }
            append("}}")
        })

        assertTrue(output.isFile && output.length() > 100)
    }

    private fun number(value: Double): String =
        String.format(Locale.US, "%.12f", value)
}
