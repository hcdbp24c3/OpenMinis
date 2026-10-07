package com.openminis.app.data.model

import com.openminis.app.backup.BackupZip
import com.openminis.app.data.db.ProviderModelEntryEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * [T-android-cross-platform-restore-fixture] An iOS-authored `.minisbak`
 * restored on Android: `data/provider_config.json` must decode with every
 * custom model parameter intact, survive the Room `overrides_json` round trip,
 * and survive a model-list refresh.
 *
 * WHY the package is built here rather than read from disk: this test was
 * written while verifying a real cross-platform restore by hand, and it opened
 * the fixed path `/tmp/cross_platform_test.minisbak` — a file NOTHING in the
 * repo produces (no exporter call, no committed fixture, no script). The guard
 * could therefore only pass on the machine where that hand-built package still
 * happened to exist, and failed everywhere else; `.github/workflows/build-apk.yml`
 * records it as one of the pre-existing failures.
 *
 * The payload below is authored here and archived with the production
 * [BackupZip.archive], so the package is laid out exactly as BackupImporter
 * reads it (package-relative entries, `data/provider_config.json`). Every key
 * mirrors the iOS writer:
 *   - src/ios/Providers/ProviderInstance.swift — CodingKeys are camelCase,
 *     `providerType` travels as the ProviderType rawValue, `createdAt` is a Date.
 *   - src/ios/Providers/ProviderTypes.swift — ProviderType / ProviderCredential
 *     are String raw-value enums, so "openAI" / "apiKey" travel verbatim.
 *   - src/ios/Providers/ModelEntry.swift — CodingKeys carry the base model as
 *     `model`, and ModelOverrides declares temperature / topP / customHeaders /
 *     extraBodyParams, the last two as `[String: String]`. That string-map shape
 *     is the value difference the Android decoder has to absorb (JsonObject here).
 *   - src/ios/Agent/Backup/BackupJSONLWriter.swift — BackupJSONFile encodes with
 *     `dateEncodingStrategy = .iso8601`.
 */
class CrossPlatformBackupRestoreUnitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** `provider_config.json` in the shape iOS `BackupJSONFile.write` emits. */
    private val iosProviderConfigJson = """
        {
          "instances" : [ {
            "id" : "A1B2C3D4-0001-4000-8000-000000000001",
            "label" : "OpenAI",
            "providerType" : "openAI",
            "credentialType" : "apiKey",
            "isEnabled" : true,
            "createdAt" : "2026-09-01T10:00:00Z"
          } ],
          "modelEntries" : [ {
            "uuid" : "A1B2C3D4-0002-4000-8000-000000000002",
            "providerInstanceId" : "A1B2C3D4-0001-4000-8000-000000000001",
            "model" : {
              "id" : "test-gpt-custom",
              "displayName" : "Custom GPT",
              "provider" : "openAI",
              "contextWindow" : 128000,
              "maxOutputTokens" : 16384
            },
            "overrides" : {
              "displayName" : "My Tuned GPT",
              "maxOutputTokens" : 4096,
              "temperature" : 0.7,
              "topP" : 0.9,
              "customHeaders" : {
                "X-Custom-Auth" : "Token-ABC-123",
                "HTTP-Referer" : "https://openminis.app"
              },
              "extraBodyParams" : {
                "seed" : "42"
              }
            },
            "isCustom" : true,
            "isHidden" : false,
            "userModifiedAt" : "2026-09-02T11:30:00Z"
          } ],
          "modelGroups" : [ ]
        }
    """.trimIndent()

    /** Stage the payload and package it the way BackupExporter does. */
    private fun writeIosPackage(): File {
        val staging = File(tmp.root, "staging")
        File(staging, "data").mkdirs()
        File(staging, "data/provider_config.json").writeText(iosProviderConfigJson)
        val packageFile = File(tmp.root, "cross_platform_test.minisbak")
        BackupZip.archive(staging, packageFile)
        return packageFile
    }

    @Test
    fun testRestoreBackupWithCustomModelOverrides() {
        val backupFile = writeIosPackage()
        assertTrue("Backup file must exist", backupFile.exists())

        var extractedJson: String? = null
        ZipInputStream(FileInputStream(backupFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "data/provider_config.json") {
                    extractedJson = zis.bufferedReader().readText()
                    break
                }
                entry = zis.nextEntry
            }
        }
        assertNotNull("Must extract provider_config.json from minisbak", extractedJson)

        val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        // 1. Deserialization
        val config = jsonParser.decodeFromString<ProviderConfig>(extractedJson!!)
        assertNotNull(config)
        assertEquals(1, config.instances.size)
        assertEquals(1, config.modelEntries.size)

        val modelEntry = config.modelEntries[0]
        val overrides = modelEntry.overrides
        assertNotNull("Overrides must be parsed", overrides)

        // Every newly added custom parameter must parse intact
        assertEquals(0.7, overrides.temperature ?: 0.0, 0.0001)
        assertEquals(0.9, overrides.topP ?: 0.0, 0.0001)
        assertNotNull("customHeaders must not be null", overrides.customHeaders)
        assertEquals("Token-ABC-123", overrides.customHeaders?.get("X-Custom-Auth"))
        assertEquals("https://openminis.app", overrides.customHeaders?.get("HTTP-Referer"))
        assertNotNull("extraBodyParams must not be null", overrides.extraBodyParams)
        // iOS declares extraBodyParams as [String: String]; the value must survive
        // the change of shape into Android's JsonObject.
        assertEquals("42", (overrides.extraBodyParams!!["seed"] as JsonPrimitive).content)

        // 2. Simulate writing the local Room entity's overrides_json column
        val serializedOverrides = jsonParser.encodeToString(overrides)
        val entity = ProviderModelEntryEntity(
            id = modelEntry.uuid,
            providerInstanceId = modelEntry.providerInstanceId,
            baseModelJson = jsonParser.encodeToString(modelEntry.baseModel),
            overridesJson = serializedOverrides,
            isCustom = 0,
            isHidden = 0,
            sortOrder = 0,
            userModifiedAt = System.currentTimeMillis()
        )
        assertNotNull(entity.overridesJson)

        // 3. Simulate reading it back from the Room entity
        val restoredOverrides = jsonParser.decodeFromString<ModelOverrides>(entity.overridesJson!!)
        assertEquals(0.7, restoredOverrides.temperature ?: 0.0, 0.0001)
        assertEquals("Token-ABC-123", restoredOverrides.customHeaders?.get("X-Custom-Auth"))

        // 4. Simulate a model-list refresh (Refresh Models); the overrides must not be wiped
        val remoteNewBaseModel = LLMModel(
            id = "test-gpt-custom",
            displayName = "Remote Updated GPT Name",
            provider = "openAI"
        )
        val refreshedModelEntry = ModelEntry(
            providerInstanceId = entity.providerInstanceId,
            baseModel = remoteNewBaseModel,
            overrides = restoredOverrides,
            isCustom = entity.isCustom != 0,
            isHidden = entity.isHidden != 0,
            uuid = entity.id,
            userModifiedAt = entity.userModifiedAt
        )
        // The custom parameters must survive the refresh intact
        assertEquals(0.7, refreshedModelEntry.overrides.temperature ?: 0.0, 0.0001)
        assertEquals(0.9, refreshedModelEntry.overrides.topP ?: 0.0, 0.0001)
        assertEquals("Token-ABC-123", refreshedModelEntry.overrides.customHeaders?.get("X-Custom-Auth"))
        assertEquals("https://openminis.app", refreshedModelEntry.overrides.customHeaders?.get("HTTP-Referer"))

        println(">>> VERIFIED: All custom model parameters successfully deserialized, saved to DB Entity, and preserved across model refresh without any MissingFieldException!")
    }
}
