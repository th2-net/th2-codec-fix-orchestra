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

import com.exactpro.th2.codec.api.IPipelineCodecSettings

data class FixOrchestraCodecSettings(
    val defaultScenario: String = "base",
    val encodeErrorAsWaring: Boolean = false,
    val decodeErrorAsWaring: Boolean = false,
    val inlineComponents: Boolean = false,
    val cacheSizeForFieldDatatype: Int = 500,
    val cacheSizeForCodeSetType: Int = 250,
    val cacheSizeForGroups: Int = 100,
    val cacheSizeForComponents: Int = 100,
    val cacheSizeForMessageMembers: Int = 100,
    val cacheSizeForDatatype: Int = 50,
) : IPipelineCodecSettings