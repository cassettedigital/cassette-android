//
//  CassetteLocalIdTest.kt
//  Tempo (Cassette fork) — Layer 1 (Identity) parity test.
//
//  Asserts the Kotlin CassetteLocalId port produces, for every vector, the same
//  id that the Swift port (CassetteLocalIDTest.swift), the Go sidecar
//  (local_id_test.go), and the TypeScript web impl assert against.
//
//  CONTRACT: `FIXTURE_JSON` below is a VERBATIM copy of the locked fixture at
//  apps/cassette/test/fixtures/local-id-vectors.json. An Android unit test can't
//  reach that file across the nested-repo boundary at runtime, so it is embedded
//  here (mirroring the iOS CassetteLocalIDTest.swift approach). If the canonical
//  fixture changes, copy it here verbatim and re-run — the two MUST stay identical.
//

package com.cappielloantonio.tempo.util

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CassetteLocalIdTest {

    private data class Vector(
        @SerializedName("name") val name: String,
        @SerializedName("artist") val artist: String,
        @SerializedName("title") val title: String,
        @SerializedName("duration_seconds") val durationSeconds: Double?,
        @SerializedName("expected_local_id") val expectedLocalId: String,
    )

    private fun loadVectors(): List<Vector> {
        val type = object : TypeToken<List<Vector>>() {}.type
        val vectors = Gson().fromJson<List<Vector>>(FIXTURE_JSON, type)
        assertTrue("fixture has no vectors", vectors.isNotEmpty())
        return vectors
    }

    @Test
    fun fixtureVectors() {
        for (v in loadVectors()) {
            // A null duration is sent as NaN, which the algorithm guards to "0",
            // matching Swift (NaN), Go (math.IsNaN) and TS (!isFinite).
            val duration = v.durationSeconds ?: Double.NaN
            val got = CassetteLocalId.compute(v.artist, v.title, duration)
            assertEquals(
                "vector \"${v.name}\": compute(${v.artist}, ${v.title}, ${v.durationSeconds})",
                v.expectedLocalId,
                got,
            )
        }
    }

    @Test
    fun intAndDoubleHelpersAgree() {
        val a = CassetteLocalId.compute("Bon Iver", "Day One", 184.0)
        val b = CassetteLocalId.compute("Bon Iver", "Day One", 184)
        assertEquals("Double and Int overloads must agree", a, b)
    }

    @Test
    fun outputShapeIs32LowercaseHex() {
        val id = CassetteLocalId.compute("X", "Y", 1)
        assertEquals("expected 32-char output", 32, id.length)
        assertTrue("non-hex char in $id", id.all { it in "0123456789abcdef" })
    }

    companion object {
        // Verbatim copy of apps/cassette/test/fixtures/local-id-vectors.json.
        private const val FIXTURE_JSON = """
        [
          {
            "name": "ascii baseline",
            "artist": "Bon Iver",
            "title": "Day One",
            "duration_seconds": 184,
            "expected_local_id": "f9fb3ba93022d599de45fc3016168ee1"
          },
          {
            "name": "diacritic strip",
            "artist": "Sigur Rós",
            "title": "Glósóli",
            "duration_seconds": 372,
            "expected_local_id": "4cb557cb6f7c6c4a10d6b9e7d3ffe59b"
          },
          {
            "name": "whitespace collapse",
            "artist": "  Bon   Iver  ",
            "title": "Day  One",
            "duration_seconds": 184,
            "expected_local_id": "f9fb3ba93022d599de45fc3016168ee1"
          },
          {
            "name": "case insensitive",
            "artist": "BON IVER",
            "title": "day one",
            "duration_seconds": 184,
            "expected_local_id": "f9fb3ba93022d599de45fc3016168ee1"
          },
          {
            "name": "duration rounding down",
            "artist": "Bon Iver",
            "title": "Day One",
            "duration_seconds": 184.4,
            "expected_local_id": "f9fb3ba93022d599de45fc3016168ee1"
          },
          {
            "name": "duration rounding up",
            "artist": "Bon Iver",
            "title": "Day One",
            "duration_seconds": 184.5,
            "expected_local_id": "5b831cbfa09a67ad6e5b100bdb47ac3b"
          },
          {
            "name": "unicode nfkc fullwidth",
            "artist": "ＦＯＮＴＡＩＮＥＳ Ｄ.Ｃ.",
            "title": "Romance",
            "duration_seconds": 200,
            "expected_local_id": "8236342b131ab02b2a8ebcfd95d50c55"
          },
          {
            "name": "empty title",
            "artist": "Various",
            "title": "",
            "duration_seconds": 1,
            "expected_local_id": "6d68c305ff4f2f73fcd1eea46a356b27"
          },
          {
            "name": "empty artist and title",
            "artist": "",
            "title": "",
            "duration_seconds": 0,
            "expected_local_id": "7d4a8d99d1d2920565f89a68db351172"
          },
          {
            "name": "long unicode title",
            "artist": "Björk",
            "title": "Jóga (Strings & Vocal)",
            "duration_seconds": 308,
            "expected_local_id": "c6361ef0692d0a6f3bae5f05a1425968"
          },
          {
            "name": "pipe and separator chars",
            "artist": "AC/DC",
            "title": "Touch Too Much",
            "duration_seconds": 269,
            "expected_local_id": "6f0429f8367668526a32524e8e05e45d"
          },
          {
            "name": "negative duration treated as 0",
            "artist": "Test",
            "title": "Test",
            "duration_seconds": -10,
            "expected_local_id": "68b14e11af6d15e022daacbcccf0b4d0"
          },
          {
            "name": "null duration treated as 0",
            "artist": "Test",
            "title": "Test",
            "duration_seconds": null,
            "expected_local_id": "68b14e11af6d15e022daacbcccf0b4d0"
          }
        ]
        """
    }
}
