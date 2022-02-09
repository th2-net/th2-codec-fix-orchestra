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

class FixOrchestraCodecFactory : IPipelineCodecFactory {
    private lateinit var context: IPipelineCodecContext

    override val settingsClass: Class<out IPipelineCodecSettings> = FixOrchestraCodecSettings::class.java
    override val protocol: String = PROTOCOL

    override fun init(context: IPipelineCodecContext) {
        this.context = context
    }

    override fun create(settings: IPipelineCodecSettings?) = FixOrchestraCodec(
        requireNotNull(settings as? FixOrchestraCodecSettings) { "settings are not an instance of ${FixOrchestraCodec::class.qualifiedName}" },
        context
    )

    companion object {
        const val PROTOCOL = "FIX"
    }
}