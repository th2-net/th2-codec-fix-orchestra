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

package com.exactpro.th2.codec.fix.orchestra.util

import com.exactpro.th2.common.grpc.ListValue
import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.Message.Builder
import com.exactpro.th2.common.message.addField
import com.exactpro.th2.common.message.messageType
import com.exactpro.th2.common.message.set
import quickfix.FieldMap
import quickfix.Group
import quickfix.field.converter.BooleanConverter
import quickfix.field.converter.DecimalConverter
import quickfix.field.converter.IntConverter
import quickfix.field.converter.UtcDateOnlyConverter
import quickfix.field.converter.UtcTimeOnlyConverter
import quickfix.field.converter.UtcTimestampConverter
import quickfix.Message as QuickfixMessage

data class DecodeResult(val message: Builder, val errors: List<String>)

fun FixMessage.decode(message: QuickfixMessage): DecodeResult {
    val errors = mutableListOf<String>()

    val bodyMessage = Message.newBuilder()
    val headerMessage = Message.newBuilder()
    val trailerMessage = Message.newBuilder()

    header.decodeMessage(message.header, headerMessage, errors, "$name.$HEADER_FIELD")
    trailer.decodeMessage(message.trailer, trailerMessage, errors, "$name.$TRAILER_FIELD")
    body.decodeMessage(message, bodyMessage, errors, name)

    bodyMessage[HEADER_FIELD] = headerMessage
    bodyMessage[TRAILER_FIELD] = trailerMessage
    bodyMessage.messageType = name

    return DecodeResult(bodyMessage, errors)
}

private fun FieldMap.isPresent(field: FixField): Boolean = when {
    field.isField -> isSetField(field.tag)
    field.isGroup -> hasGroup(1, field.tag)
    else -> field.fields.values.any(::isPresent)
}

private fun Map<String, FixField>.decodeMessage(
    message: FieldMap,
    target: Builder,
    errors: MutableList<String>,
    path: String,
    checkPresence: Boolean = true,
) {
    for ((name, field) in this) {
        when (name) {
            HEADER_COMPONENT, HEADER_FIELD, TRAILER_COMPONENT, TRAILER_FIELD -> continue
        }

        if (!message.isPresent(field)) {
            if (checkPresence && field.isRequired) {
                errors += "Missing required field: $path.$name"
            }

            continue
        }

        when {
            field.isField -> field.decodeField(message.getString(field.tag), target, errors, path)
            field.isComponent -> target[name] = Message.newBuilder().apply {
                field.fields.decodeMessage(message, this, errors, "$path.$name", checkPresence)
            }
            field.isGroup -> target[name] = ListValue.newBuilder().apply {
                field.decodeGroups(message.getGroups(field.tag), this, errors, path, checkPresence)
            }
        }
    }
}

private fun FixField.decodeField(
    value: String,
    target: Builder,
    errors: MutableList<String>,
    path: String,
) {
    if (isEnum) {
        when (val value = reversedValues[value] ?: value) {
            in reversedValues.values -> target.addField(name, value)
            else -> errors += "Out of range value '$value' at: $path.$name"
        }

        return
    }

    when (type) {
        "Boolean" -> {
            value.runCatching(BooleanConverter::convert)
                .onSuccess { target.addField(name, it) }
                .onFailure { errors += "Invalid boolean value '$value' at: $path.$name" }
        }
        "int", "Length", "NumInGroup", "SeqNum" -> {
            value.runCatching(IntConverter::convert)
                .onSuccess { target.addField(name, it) }
                .onFailure { errors += "Invalid integer value '$value' at: $path.$name" }
        }
        "float", "Amt", "Price", "PriceOffset", "Qty", "Percentage" -> {
            value.runCatching(DecimalConverter::convert)
                .onSuccess { target.addField(name, it) }
                .onFailure { errors += "Invalid decimal value '$value' at: $path.$name" }
        }
        "UTCDateOnly" -> {
            value.runCatching(UtcDateOnlyConverter::convertToLocalDate)
                .onSuccess { target.addField(name, it) }
                .onFailure { errors += "Invalid date-only value '$value' at: $path.$name" }
        }
        "UTCTimeOnly" -> {
            value.runCatching(UtcTimeOnlyConverter::convertToLocalTime)
                .onSuccess { target.addField(name, it) }
                .onFailure { errors += "Invalid time-only value '$value' at: $path.$name" }
        }
        "UTCTimestamp" -> {
            value.runCatching(UtcTimestampConverter::convertToLocalDateTime)
                .onSuccess { target.addField(name, it) }
                .onFailure { errors += "Invalid date-time value '$value' at: $path.$name" }
        }
        else -> target.addField(name, value)
    }
}

private fun FixField.decodeGroups(
    groups: List<Group>,
    target: ListValue.Builder,
    errors: MutableList<String>,
    path: String,
    checkPresence: Boolean,
) = groups.forEachIndexed { index, value ->
    fields.decodeMessage(
        value,
        target.addValuesBuilder().messageValueBuilder,
        errors,
        "$path.$name[$index]",
        checkPresence
    )
}