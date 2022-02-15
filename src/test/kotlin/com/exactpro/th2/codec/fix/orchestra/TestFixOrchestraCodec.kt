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

import com.exactpro.th2.codec.api.DictionaryAlias
import com.exactpro.th2.codec.api.IPipelineCodecContext
import com.exactpro.th2.common.grpc.AnyMessage
import com.exactpro.th2.common.grpc.MessageGroup
import com.exactpro.th2.common.grpc.RawMessage
import com.exactpro.th2.common.message.messageType
import com.exactpro.th2.common.schema.dictionary.DictionaryType
import com.google.protobuf.ByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.InputStream

class TestFixOrchestraCodec {
    private val factory = FixOrchestraCodecFactory().apply {
        init(object : IPipelineCodecContext {

            override fun get(alias: DictionaryAlias): InputStream {
                TODO("Not yet implemented")
            }

            override fun get(type: DictionaryType): InputStream {
                return checkNotNull(TestFixOrchestraCodec::class.java.classLoader.getResourceAsStream("dict/mit_2016.xml")) {
                    "cannot find dictionary"
                }
            }

            override fun getDictionaryAliases(): Set<String> {
                TODO("Not yet implemented")
            }
        })
    }

    @AfterEach
    fun tearDown() {
        factory.close()
    }

    @Test
    fun `decodes business message`() {
        val codec = factory.create(FixOrchestraCodecSettings())
        val message = "8=FIXT.1.1\u00019=313\u000135=8\u000134=92\u000149=FGW\u000152=20220214-12:23:36.900\u000156=DEMO-CONN2\u000111=3016560\u000114=40\u000117=156\u000122=8\u000137=54\u000138=100\u000139=C\u000140=2\u000144=34\u000148=INSTR2\u000154=2\u000158=The remaining part of simulated order has been expired\u000159=3\u000160=20220214-12:23:36.798\u0001150=C\u0001151=0\u0001528=A\u0001581=1\u0001453=3\u0001448=DEMO-CONN2\u0001447=D\u0001452=76\u0001448=0\u0001447=N\u0001452=3\u0001448=3\u0001447=N\u0001452=12\u000110=035\u0001"

        val result = codec.decode(
            MessageGroup.newBuilder()
                .addMessages(
                    AnyMessage.newBuilder()
                        .setRawMessage(
                            RawMessage.newBuilder()
                                .setBody(ByteString.copyFrom(message.toByteArray(Charsets.UTF_8)))
                        )
                        .build()
                )
                .build()
        )
        val anyMessage = result.messagesList.single()
        Assertions.assertTrue(anyMessage.hasMessage()) { "message does not have parsed message: $anyMessage" }
        val parsedMessage = anyMessage.message
        Assertions.assertEquals("ExecutionReport", parsedMessage.messageType)
    }

    @Test
    fun `decodes session message`() {
        val codec = factory.create(FixOrchestraCodecSettings())
        val message = "8=FIXT.1.1\u00019=59\u000135=0\u000134=1525\u000149=DEMO-CONN1\u000152=20220214-12:23:14.181\u000156=FGW\u000110=077\u0001"

        val result = codec.decode(
            MessageGroup.newBuilder()
                .addMessages(
                    AnyMessage.newBuilder()
                        .setRawMessage(
                            RawMessage.newBuilder()
                                .setBody(ByteString.copyFrom(message.toByteArray(Charsets.UTF_8)))
                        )
                        .build()
                )
                .build()
        )
        val anyMessage = result.messagesList.single()
        Assertions.assertTrue(anyMessage.hasMessage()) { "message does not have parsed message: $anyMessage" }
        val parsedMessage = anyMessage.message
        Assertions.assertEquals("Heartbeat", parsedMessage.messageType)
    }
}