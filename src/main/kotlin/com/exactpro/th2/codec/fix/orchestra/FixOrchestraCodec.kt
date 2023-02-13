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

import com.exactpro.th2.codec.ValidateException
import com.exactpro.th2.codec.api.IPipelineCodec
import com.exactpro.th2.codec.api.IReportingContext
import com.exactpro.th2.codec.fix.orchestra.FixOrchestraCodecFactory.Companion.PROTOCOL
import com.exactpro.th2.codec.fix.orchestra.util.FixMessage
import com.exactpro.th2.codec.fix.orchestra.util.beginString
import com.exactpro.th2.codec.fix.orchestra.util.decode
import com.exactpro.th2.codec.fix.orchestra.util.details
import com.exactpro.th2.codec.fix.orchestra.util.encode
import com.exactpro.th2.codec.fix.orchestra.util.loadMessageStructures
import com.exactpro.th2.codec.fix.orchestra.validator.ValidatorQfj
import com.exactpro.th2.common.grpc.Direction
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.direction
import com.exactpro.th2.common.message.messageType
import com.exactpro.th2.common.message.plusAssign
import com.exactpro.th2.common.message.toJson
import com.google.protobuf.ByteString
import io.fixprotocol._2020.orchestra.repository.Repository
import io.fixprotocol.orchestra.model.SymbolResolver
import mu.KotlinLogging
import org.quickfixj.CharsetSupport
import quickfix.DataDictionary
import quickfix.field.MsgType
import com.exactpro.th2.codec.fix.orchestra.validator.RepositoryCache
import com.exactpro.th2.codec.fix.orchestra.validator.TestExceptionImpl
import kotlin.text.Charsets.UTF_8
import quickfix.Message as QuickfixMessage

class FixOrchestraCodec(
    private val settings: FixOrchestraCodecSettings,
    private val dictionary: DataDictionary,
    repository: Repository,
) : IPipelineCodec {
    private val logger = KotlinLogging.logger {}

    private val cacheAccessor = RepositoryCache(repository, settings.cacheSize)
    private val validator = ValidatorQfj(cacheAccessor, SymbolResolver())

    private val structuresByName = repository.loadMessageStructures(settings.inlineComponents)
    private val structuresByType = structuresByName.values.associateBy(FixMessage::type)

    private val beginString = repository.beginString

    init {
        CharsetSupport.setCharset(UTF_8.toString())
    }

    override fun encode(messageGroup: MessageGroup): MessageGroup = throw UnsupportedOperationException("use encode with context instead")

    override fun encode(messageGroup: MessageGroup, context: IReportingContext): MessageGroup {
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
            val errors = if (settings.encodeErrorAsWaring) ContextHolder(context) else ListHolder()
            val (result, encodeErrors) = structure.encode(parsed, beginString)
            errors += encodeErrors
            val metadata = parsed.metadata

            try {
                val scenario = metadata.getPropertiesOrDefault(SCENARIO_PROPERTY, settings.defaultScenario)
                val type = checkNotNull(cacheAccessor.getMessage(name, scenario)) { "No scenario $scenario for message: $name" }
                validator.validate(result, type)
                dictionary.validate(result, true)
            } catch (e: TestExceptionImpl) {
                throw ValidateException("msgType [${e.msgType}] tags [${e.tags.joinToString()}], scenario [${e.scenario}]", e.details)
            } catch (e: Exception) {
                logger.error(e) { "Failed to validate encoded message" }
                errors += "Encoded message validation error: ${e.message}"
            }

            if(errors.hasErrors) {
                error("Failed to encode message due to following errors:\n${errors.joinToString("\n") { " - $it" }}")
            }

            builder += RawMessage.newBuilder().apply {
                body = ByteString.copyFrom(result.toString(), UTF_8)
                if(parsed.hasParentEventId()) parentEventId = parsed.parentEventId
                metadataBuilder.apply {
                    putAllProperties(metadata.propertiesMap)
                    this.id = metadata.id
                    this.protocol = PROTOCOL
                }
            }
        }

        return builder.build()
    }

    override fun decode(messageGroup: MessageGroup): MessageGroup = throw UnsupportedOperationException("use decode with context instead")

    override fun decode(messageGroup: MessageGroup, context: IReportingContext): MessageGroup {
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
            val errors = when {
                settings.encodeErrorAsWaring && raw.wasSentByTh2 -> DummyHolder()
                settings.decodeErrorAsWaring -> ContextHolder(context)
                else -> ListHolder()
            }

            try {
                val scenario = metadata.getPropertiesOrDefault(SCENARIO_PROPERTY, settings.defaultScenario)
                val type = checkNotNull(cacheAccessor.getMessage(structure.name, scenario)) { "No scenario $scenario for message: ${structure.name}" }
                validator.validate(quickfixMessage, type)
            } catch (e: TestExceptionImpl) {
                throw ValidateException("msgType [${e.msgType}], tags [${e.tags.joinToString()}], scenario [${e.scenario}]", e.details)
            } catch (e: Exception) {
                logger.error(e) { "Failed to validate decoded message" }
                errors += "Decoded message validation error: ${e.message}"
            }

            val result = structure.decode(quickfixMessage).apply { errors += this.errors }.message

            if(errors.hasErrors) {
                error("Failed to decode message due to following errors:\n${errors.joinToString("\n") { " - $it" }}")
            }

            builder += result.apply {
                if(raw.hasParentEventId()) parentEventId = raw.parentEventId
                metadataBuilder.apply {
                    putAllProperties(metadata.propertiesMap)
                    this.id = metadata.id
                    this.protocol = PROTOCOL
                }
            }
        }

        return builder.build()
    }

    private interface ErrorHolder : Iterable<String> {
        operator fun plusAssign(message: String)
        operator fun plusAssign(messages: Collection<String>)
        val hasErrors: Boolean
    }

    private class DummyHolder : ErrorHolder {
        override fun plusAssign(message: String) {
            LOGGER.warn { "A waring was reported: $message" }
        }

        override fun plusAssign(messages: Collection<String>) {
            LOGGER.warn { "${messages.size} warning(s) were reported: ${messages.joinToString("; ")}" }
        }

        override val hasErrors: Boolean
            get() = false

        override fun iterator(): Iterator<String> = emptyList<String>().iterator()

    }

    private class ListHolder : ErrorHolder {
        private val _errors = mutableListOf<String>()
        override fun plusAssign(message: String) {
            _errors += message
        }
        override fun plusAssign(messages: Collection<String>) {
            _errors += messages
        }
        override val hasErrors: Boolean
            get() = _errors.isNotEmpty()

        override fun iterator(): Iterator<String> = _errors.iterator()
    }

    private class ContextHolder(private val context: IReportingContext) : ErrorHolder {
        override fun plusAssign(message: String): Unit = context.warning(message)

        override fun plusAssign(messages: Collection<String>): Unit = context.warnings(messages)

        override val hasErrors: Boolean
            get() = false

        override fun iterator(): Iterator<String> = emptyList<String>().iterator()
    }

    companion object {
        private const val SCENARIO_PROPERTY = "th2.codec.orchestra.scenario"
        private val LOGGER = KotlinLogging.logger { }

        private val RawMessage.wasSentByTh2: Boolean
            get() = hasParentEventId() && direction == Direction.SECOND
    }
}
