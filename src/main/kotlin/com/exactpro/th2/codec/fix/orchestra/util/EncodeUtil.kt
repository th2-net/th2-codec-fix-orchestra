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

import com.exactpro.th2.common.grpc.Message
import com.exactpro.th2.common.grpc.Value
import com.exactpro.th2.common.grpc.Value.KindCase.LIST_VALUE
import com.exactpro.th2.common.grpc.Value.KindCase.MESSAGE_VALUE
import com.exactpro.th2.common.grpc.Value.KindCase.NULL_VALUE
import com.exactpro.th2.common.grpc.Value.KindCase.SIMPLE_VALUE
import com.exactpro.th2.common.value.getMessage
import quickfix.FieldMap
import quickfix.Group
import quickfix.UtcTimestampPrecision
import quickfix.field.BeginString
import quickfix.field.MsgType
import quickfix.field.converter.BooleanConverter
import quickfix.field.converter.DecimalConverter
import quickfix.field.converter.IntConverter
import quickfix.field.converter.UtcDateOnlyConverter
import quickfix.field.converter.UtcTimeOnlyConverter
import quickfix.field.converter.UtcTimestampConverter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import quickfix.Message as QuickfixMessage

data class EncodeResult(val message: QuickfixMessage, val errors: List<String>)

fun FixMessage.encode(message: Message, beginString: String): EncodeResult {
    val errors = mutableListOf<String>()

    val bodyFields = message.fieldsMap
    val headerFields = bodyFields[HEADER_FIELD]?.getMessage()?.fieldsMap
    val trailerFields = bodyFields[TRAILER_FIELD]?.getMessage()?.fieldsMap

    val target = Th2QfjMessage(headerFieldOrder, bodyFieldOrder, trailerFieldOrder)

    headerFields?.apply {
        header.encodeMessage(this, target.header, errors, "$name.$HEADER_FIELD", false)
    }

    target.header.run {
        setString(BeginString.FIELD, beginString)
        setString(MsgType.FIELD, type)
    }

    body.encodeMessage(bodyFields, target, errors, name)

    trailerFields?.apply {
        trailer.encodeMessage(this, target.trailer, errors, "$name.$TRAILER_FIELD", false)
    }

    return EncodeResult(target, errors)
}

private fun Map<String, FixField>.encodeMessage(
    message: Map<String, Value>,
    target: FieldMap,
    errors: MutableList<String>,
    path: String,
    checkPresence: Boolean = true,
) {
    for (name in (keys + message.keys)) {
        when (name) {
            HEADER_COMPONENT, HEADER_FIELD, TRAILER_COMPONENT, TRAILER_FIELD -> continue
        }

        val field = this[name]

        if (field == null) {
            errors += "Unexpected field: $path.$name"
            continue
        }

        val value = message[name]

        if (value == null || value.kindCase == NULL_VALUE) {
            if (checkPresence && field.isRequired) {
                errors += "Missing required field: $path.$name"
            }

            continue
        }

        when {
            field.isField -> when (value.kindCase) {
                SIMPLE_VALUE -> field.encodeField(value.simpleValue, target, errors, path)
                else -> errors += "Expected $SIMPLE_VALUE but got ${value.kindCase} at: $path.$name"
            }
            field.isComponent -> when (value.kindCase) {
                MESSAGE_VALUE -> field.fields.encodeMessage(value.messageValue.fieldsMap, target, errors, "$path.$name", checkPresence)
                else -> errors += "Expected $MESSAGE_VALUE but got ${value.kindCase} at: $path.$name"
            }
            field.isGroup -> when (value.kindCase) {
                LIST_VALUE -> field.encodeGroups(value.listValue.valuesList, target, errors, path, checkPresence)
                else -> errors += "Expected $LIST_VALUE but got ${value.kindCase} at: $path.$name"
            }
        }
    }
}

private fun FixField.encodeField(
    value: String,
    target: FieldMap,
    errors: MutableList<String>,
    path: String,
) {
    if (isEnum) {
        when (val value = values[value] ?: value) {
            in values.values -> target.setString(tag, value)
            else -> errors += "Out of range value '$value' at: $path.$name"
        }

        return
    }

    when (type) {
        "Boolean" -> {
            value.runCatching(String::toBooleanExact)
                .recoverCatching { BooleanConverter.convert(value) }
                .onSuccess { target.setBoolean(tag, it) }
                .onFailure { errors += "Invalid boolean value '$value' at: $path.$name" }
        }
        "int", "Length", "NumInGroup", "SeqNum" -> {
            value.runCatching(IntConverter::convert)
                .onSuccess { target.setInt(tag, it) }
                .onFailure { errors += "Invalid integer value '$value' at: $path.$name" }
        }
        "float", "Amt", "Price", "PriceOffset", "Qty", "Percentage" -> {
            value.runCatching(DecimalConverter::convert)
                .onSuccess { target.setDecimal(tag, it) }
                .onFailure { errors += "Invalid decimal value '$value' at: $path.$name" }
        }
        "UTCDateOnly" -> {
            value.runCatching(LocalDate::parse)
                .recoverCatching { UtcDateOnlyConverter.convertToLocalDate(value) }
                .onSuccess { target.setUtcDateOnly(tag, it) }
                .onFailure { errors += "Invalid date-only value '$value' at: $path.$name" }
        }
        "UTCTimeOnly" -> {
            value.runCatching(LocalTime::parse)
                .recoverCatching { UtcTimeOnlyConverter.convertToLocalTime(value) }
                .onSuccess { target.setUtcTimeOnly(tag, it, it.calculateTimeOnlyPrecision()) }
                .onFailure { errors += "Invalid time-only value '$value' at: $path.$name" }
        }
        "UTCTimestamp" -> {
            value.runCatching(LocalDateTime::parse)
                .recoverCatching { UtcTimestampConverter.convertToLocalDateTime(value) }
                .onSuccess { target.setUtcTimeStamp(tag, it, it.calculateTimestampPrecision()) }
                .onFailure { errors += "Invalid date-time value '$value' at: $path.$name" }
        }
        else -> target.setString(tag, value)
    }
}

private fun LocalTime.calculateTimeOnlyPrecision(): UtcTimestampPrecision {
    return calculateTimePrecision(nano)
}

private fun LocalDateTime.calculateTimestampPrecision(): UtcTimestampPrecision {
    return calculateTimePrecision(nano)
}

private fun calculateTimePrecision(nanos: Int): UtcTimestampPrecision {
    return when (nanos) {
        0 -> UtcTimestampPrecision.SECONDS
        in 1..999 -> UtcTimestampPrecision.NANOS
        in 1_000..999_999 -> UtcTimestampPrecision.MICROS
        in 1_000_000..999_999_999 -> UtcTimestampPrecision.MILLIS
        else -> error("nanos part is negative")
    }
}

private fun FixField.encodeGroups(
    groups: List<Value>,
    target: FieldMap,
    errors: MutableList<String>,
    path: String,
    checkPresence: Boolean,
) {
    val counterTag = tag
    val fieldOrder = fieldOrder

    for ((index, value) in groups.withIndex()) {
        if (!value.hasMessageValue()) {
            errors += "Expected $MESSAGE_VALUE but got ${value.kindCase} at: $path.$name[$index]"
            continue
        }

        val group = Group(counterTag, fieldOrder[0], fieldOrder)
        fields.encodeMessage(value.messageValue.fieldsMap, group, errors, "$path.$name[$index]", checkPresence)
        target.addGroup(group)
    }
}

private fun String.toBooleanExact(): Boolean = when (this) {
    "true" -> true
    "false" -> false
    else -> error("Invalid boolean value: $this")
}

private class Th2QfjMessage(
    headerFieldOrder: IntArray,
    bodyFieldOrder: IntArray,
    trailerFieldOrder: IntArray,
) : QuickfixMessage(bodyFieldOrder) {
    init {
        header = Header(headerFieldOrder)
        trailer = Trailer(trailerFieldOrder)
    }
}