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

import io.fixprotocol._2020.orchestra.repository.CodeSetType
import io.fixprotocol._2020.orchestra.repository.ComponentRefType
import io.fixprotocol._2020.orchestra.repository.ComponentType
import io.fixprotocol._2020.orchestra.repository.FieldRefType
import io.fixprotocol._2020.orchestra.repository.FieldType
import io.fixprotocol._2020.orchestra.repository.GroupRefType
import io.fixprotocol._2020.orchestra.repository.GroupType
import io.fixprotocol._2020.orchestra.repository.PresenceT.REQUIRED
import io.fixprotocol._2020.orchestra.repository.Repository
import java.io.InputStream
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import javax.xml.bind.JAXBContext

private val CODE_SETS = ConcurrentHashMap<String, CodeSetType?>()
private val FIELDS = ConcurrentHashMap<BigInteger, FieldType>()
private val COMPONENTS = ConcurrentHashMap<BigInteger, ComponentType>()
private val GROUPS = ConcurrentHashMap<BigInteger, GroupType>()

const val HEADER_COMPONENT = "StandardHeader"
const val HEADER_FIELD = "header"
const val TRAILER_COMPONENT = "StandardTrailer"
const val TRAILER_FIELD = "trailer"

val Repository.beginString: String
    get() = if (version.startsWith("FIX.5")) "FIXT.1.1" else version

fun InputStream.loadRepository(): Repository {
    val context = JAXBContext.newInstance(Repository::class.java)
    return context.createUnmarshaller().unmarshal(this) as Repository
}

data class FixMessage(
    val name: String,
    val type: String,
    val header: Map<String, FixField>,
    val body: Map<String, FixField>,
    val trailer: Map<String, FixField>,
) {
    val headerFieldOrder: IntArray = header.getFieldOrder().toList().toIntArray()
    val bodyFieldOrder: IntArray = body.getFieldOrder().toList().toIntArray()
    val trailerFieldOrder: IntArray = trailer.getFieldOrder().toList().toIntArray()
}

data class FixField(
    val name: String,
    val tag: Int = 0,
    val type: String? = null,
    val fields: Map<String, FixField> = mapOf(),
    val values: Map<String, String> = mapOf(),
    val isRequired: Boolean = false,
    val isField: Boolean = false,
    val isGroup: Boolean = false,
    val isComponent: Boolean = false,
) {
    val fieldOrder: IntArray = fields.getFieldOrder().toList().toIntArray()
    val isEnum: Boolean = values.isNotEmpty()
    val reversedValues: Map<String, String> = values.entries.associate { (key, value) -> value to key }
}

fun Map<String, FixField>.getFieldOrder(): Sequence<Int> = sequence {
    forEach { (_, field) ->
        if (field.tag > 0) yield(field.tag)
        yieldAll(field.fields.getFieldOrder())
    }
}

fun Repository.toField(reference: FieldRefType): FixField {
    val field = FIELDS.computeIfAbsent(reference.id) { id ->
        fields.field.single { id == it.id }
    }

    val codeSet = CODE_SETS.computeIfAbsent(field.type) { type ->
        codeSets.codeSet.find { it.name == type }
    }

    return FixField(
        name = field.name,
        tag = field.id.toInt(),
        type = codeSet?.type ?: field.type,
        values = codeSet?.code?.associate { it.name to it.value } ?: mapOf(),
        isRequired = reference.presence == REQUIRED,
        isField = true
    )
}

fun Repository.toField(reference: ComponentRefType, inlineComponents: Boolean): Collection<FixField> {
    val component = COMPONENTS.computeIfAbsent(reference.id) { id ->
        components.component.single { id == it.id }
    }

    val ignoreInline = component.run { name == HEADER_COMPONENT || name == TRAILER_COMPONENT }

    val fields = toFieldMap(component.componentRefOrGroupRefOrFieldRef, inlineComponents)

    return if (inlineComponents && !ignoreInline) {
        fields.values
    } else {
        listOf(
            FixField(
                name = component.name,
                isRequired = reference.presence == REQUIRED && fields.values.any(FixField::isRequired),
                isComponent = true,
                fields = fields
            )
        )
    }
}

fun Repository.toField(reference: GroupRefType, inlineComponents: Boolean): FixField {
    val group = GROUPS.computeIfAbsent(reference.id) { id ->
        groups.group.single { id == it.id }
    }

    val counter = FIELDS.computeIfAbsent(group.numInGroup.id) { id ->
        fields.field.single { id == it.id }
    }

    val field = FixField(
        name = counter.name,
        tag = counter.id.toInt(),
        isRequired = reference.presence == REQUIRED,
        isGroup = true,
        fields = toFieldMap(group.componentRefOrGroupRefOrFieldRef, inlineComponents)
    )
    if (inlineComponents) {
        return field
    }

    return FixField(
        name = group.name,
        isRequired = reference.presence == REQUIRED,
        isComponent = true,
        fields = mapOf(field.name to field)
    )
}

fun Repository.toField(value: Any, inlineComponents: Boolean): Collection<FixField> = when (value) {
    is FieldRefType -> listOf(toField(value))
    is GroupRefType -> listOf(toField(value, inlineComponents))
    is ComponentRefType -> toField(value, inlineComponents)
    else -> error("Cannot convert to field: $this")
}

fun Repository.toFieldMap(references: List<Any>, inlineComponents: Boolean): Map<String, FixField> {
    return references.asSequence()
        .flatMap { toField(it, inlineComponents) }
        .associateBy(FixField::name)
}

fun Repository.loadMessageStructures(inlineComponents: Boolean): Map<String, FixMessage> {
    val messageFields = HashMap<String, MutableMap<String, FixField>>()

    messages.message.forEach { message ->
        val fields = messageFields.getOrPut(message.name, ::LinkedHashMap)

        message.structure
            .componentRefOrGroupRefOrFieldRef
            .asSequence()
            .flatMap { toField(it, inlineComponents) }
            .associateByTo(fields, FixField::name)
    }

    return messageFields.mapValues { (name, fields) ->
        FixMessage(
            name = name,
            type = messages.message.first { it.name == name }.msgType,
            header = fields.remove(HEADER_COMPONENT)?.fields ?: error("No header in message: $name"),
            trailer = fields.remove(TRAILER_COMPONENT)?.fields ?: error("No trailer in message: $name"),
            body = fields
        )
    }
}