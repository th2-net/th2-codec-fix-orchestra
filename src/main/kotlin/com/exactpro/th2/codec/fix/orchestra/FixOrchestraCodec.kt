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

import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IPipelineCodecContext
import com.exactpro.th2.codec.fix.orchestra.FixOrchestraCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.codec.fix.orchestra.util.FixMessage
import com.exactpro.th2.codec.fix.orchestra.util.beginString
import com.exactpro.th2.codec.fix.orchestra.util.decode
import com.exactpro.th2.codec.fix.orchestra.util.details
import com.exactpro.th2.codec.fix.orchestra.util.encode
import com.exactpro.th2.codec.fix.orchestra.util.loadMessageStructures
import com.exactpro.th2.codec.fix.orchestra.util.loadRepository
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.messageType
import com.exactpro.th2.common.message.plusAssign
import com.exactpro.th2.common.message.toJson
import com.exactpro.th2.common.schema.dictionary.DictionaryType.MAIN
import com.google.protobuf.ByteString
import io.fixprotocol._2020.orchestra.repository.Repository
import io.fixprotocol.orchestra.message.TestException
import io.fixprotocol.orchestra.model.SymbolResolver
import io.fixprotocol.orchestra.model.quickfix.QuickfixValidator
import io.fixprotocol.orchestra.model.quickfix.RepositoryAccessor
import mu.KotlinLogging
import org.quickfixj.CharsetSupport
import quickfix.DataDictionary
import quickfix.field.MsgType
import kotlin.text.Charsets.UTF_8
import quickfix.Message as QuickfixMessage

class FixOrchestraCodec(
    private val settings: FixOrchestraCodecSettings,
    private val dictionary: DataDictionary,
    repository: Repository,
) : IPipelineCodec {
    private val logger = KotlinLogging.logger {}

    private val accessor = RepositoryAccessor(repository)
    private val validator = QuickfixValidator(accessor, SymbolResolver())

    private val structuresByName = repository.loadMessageStructures()
    private val structuresByType = structuresByName.values.associateBy(FixMessage::type)

    private val beginString = repository.beginString

    init {
        CharsetSupport.setCharset(UTF_8.toString())
    }

    override fun encode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList

        if (messages.isEmpty()) {
            return messageGroup
        }

        val builder = MessageGroup.newBuilder()

        for (message in messages) {
            if(!message.hasMessage()) {
                builder.addMessages(message)
                continue
            }

            val parsed = message.message
            val protocol = parsed.metadata.protocol

            if(protocol.isNotBlank() && protocol != PROTOCOL) {
                builder.addMessages(message)
                continue
            }

            logger.trace { "Encoding message: ${message.toJson()}" }

            val name = parsed.messageType
            val structure = requireNotNull(structuresByName[name]) { "Unknown message type: $name " }
            var (result, errors) = structure.encode(parsed, beginString)
            val metadata = parsed.metadata

            try {
                val scenario = metadata.getPropertiesOrDefault(SCENARIO_PROPERTY, settings.defaultScenario)
                val type = checkNotNull(accessor.getMessage(name, scenario)) { "No scenario $scenario for message: $name" }
                validator.validate(result, type)
                dictionary.validate(result, true)
            } catch (e: TestException) {
                errors += e.details
            } catch (e: Exception) {
                logger.error(e) { "Failed to validate encoded message" }
                errors += "Encoded message validation error: ${e.message}"
            }

            if(errors.isNotEmpty()) {
                error("Failed to encode message due to following errors:\n${errors.joinToString("\n") { " - $it" }}")
            }

            builder += RawMessage.newBuilder().apply {
                body = ByteString.copyFrom(result.toString(), UTF_8)
                if(parsed.hasParentEventId()) parentEventId = parsed.parentEventId
                metadataBuilder.apply {
                    putAllProperties(metadata.propertiesMap)
                    this.id = metadata.id
                    this.timestamp = metadata.timestamp
                    this.protocol = PROTOCOL
                }
            }
        }

        return builder.build()
    }

    override fun decode(messageGroup: MessageGroup): MessageGroup {
        val messages = messageGroup.messagesList

        if (messages.isEmpty()) {
            return messageGroup
        }

        val builder = MessageGroup.newBuilder()

        for (message in messages) {
            if(!message.hasRawMessage()) {
                builder.addMessages(message)
                continue
            }

            val raw = message.rawMessage
            val protocol = raw.metadata.protocol

            if(protocol.isNotBlank() && protocol != PROTOCOL) {
                builder.addMessages(message)
                continue
            }

            logger.trace { "Decoding message: ${raw.toJson()}" }

            val quickfixMessage = try {
                QuickfixMessage().apply { fromString(raw.body.toString(UTF_8), dictionary, true) }
            } catch (e: Exception) {
                throw IllegalStateException("Failed to decode message", e)
            }

            val messageType = quickfixMessage.header.getString(MsgType.FIELD)
            val structure = requireNotNull(structuresByType[messageType]) { "Unknown message type: $messageType" }
            val metadata = raw.metadata
            val errors = mutableListOf<String>()

            try {
                val scenario = metadata.getPropertiesOrDefault(SCENARIO_PROPERTY, settings.defaultScenario)
                val type = checkNotNull(accessor.getMessage(structure.name, scenario)) { "No scenario $scenario for message: ${structure.name}" }
                validator.validate(quickfixMessage, type)
            } catch (e: TestException) {
                errors += e.details
            } catch (e: Exception) {
                logger.error(e) { "Failed to validate decoded message" }
                errors += "Decoded message validation error: ${e.message}"
            }

            val result = structure.decode(quickfixMessage).apply { errors += this.errors }.message

            if(errors.isNotEmpty()) {
                error("Failed to decode message due to following errors:\n${errors.joinToString("\n") { " - $it" }}")
            }

            builder += result.apply {
                if(raw.hasParentEventId()) parentEventId = raw.parentEventId
                metadataBuilder.apply {
                    putAllProperties(metadata.propertiesMap)
                    this.id = metadata.id
                    this.timestamp = metadata.timestamp
                    this.protocol = PROTOCOL
                }
            }
        }

        return builder.build()
    }

    companion object {
        private const val SCENARIO_PROPERTY = "scenario"
    }
}
