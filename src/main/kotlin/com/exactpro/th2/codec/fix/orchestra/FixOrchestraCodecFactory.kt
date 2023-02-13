/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exactpro.th2.codec.fix.orchestra

import com.exactpro.th2.codec.api.IPipelineCodecContext
import com.exactpro.th2.codec.api.IPipelineCodecFactory
import com.exactpro.th2.codec.api.IPipelineCodecSettings
import com.exactpro.th2.codec.fix.orchestra.util.loadRepository
import com.exactpro.th2.common.schema.dictionary.DictionaryType
import com.google.auto.service.AutoService
import io.fixprotocol._2020.orchestra.repository.Repository
import mu.KotlinLogging
import quickfix.DataDictionary
import java.io.File
import java.nio.file.Files

@AutoService(IPipelineCodecFactory::class)
class FixOrchestraCodecFactory : IPipelineCodecFactory {
    private lateinit var qfjDictionaryPath: File
    private lateinit var dictionary: DataDictionary
    private lateinit var repository: Repository

    override val settingsClass: Class<out IPipelineCodecSettings> = FixOrchestraCodecSettings::class.java
    override val protocol: String
        get() = PROTOCOL
    override val protocols: Set<String> = setOf(PROTOCOL)

    override fun init(context: IPipelineCodecContext) {
        qfjDictionaryPath = Files.createTempDirectory("qfj-dictionary").toFile()
        dictionary = context[DictionaryType.MAIN].use { QfjDictionaryLoader.load(it, qfjDictionaryPath) }.inputStream().use(::DataDictionary)
        repository = context[DictionaryType.MAIN].loadRepository()
    }

    override fun create(settings: IPipelineCodecSettings?) = FixOrchestraCodec(
        requireNotNull(settings as? FixOrchestraCodecSettings) { "settings are not an instance of ${FixOrchestraCodecSettings::class.qualifiedName}" },
        dictionary,
        repository
    )

    override fun close() {
        if (::qfjDictionaryPath.isInitialized) {
            LOGGER.info { "Clearing the tmp directory $qfjDictionaryPath" }
            qfjDictionaryPath.deleteRecursively()
            LOGGER.info { "Directory cleaned" }
        }
    }

    companion object {
        const val PROTOCOL = "FIX"
        private val LOGGER = KotlinLogging.logger { }
    }
}