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

import io.fixprotocol.orchestra.quickfix.DataDictionaryGenerator
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

object QfjDictionaryLoader {
    fun load(source: InputStream): File {
        val tempDirectory = Files.createTempDirectory("qfj-dictionary").toFile()

        DataDictionaryGenerator().generate(source, tempDirectory)

        val dictionaryFile = tempDirectory.walk().single(File::isFile)
        val dictionary = dictionaryFile.loadXml()

        val header = dictionary["/fix/header"]
        val headerComponent = dictionary["/fix/components/component[@name = 'StandardHeader']"]

        val trailer = dictionary["/fix/trailer"]
        val trailerComponent = dictionary["/fix/components/component[@name = 'StandardTrailer']"]

        headerComponent.copyChildrenTo(header)
        headerComponent.remove()

        trailerComponent.copyChildrenTo(trailer)
        trailerComponent.remove()

        dictionaryFile.saveXml(dictionary)

        return dictionaryFile
    }
}

private fun File.loadXml(): Document {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(this)
}

private fun File.saveXml(node: Node) = writer().use { writer ->
    TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        setOutputProperty(OutputKeys.INDENT, "yes")
        transform(DOMSource(node), StreamResult(writer))
    }
}

private operator fun Node.get(xpath: String): Node {
    return XPathFactory.newInstance().newXPath().compile(xpath).evaluate(this, XPathConstants.NODE) as Node
}

private fun Node.remove() = parentNode.removeChild(this)

private fun Node.copyChildrenTo(target: Node) = childNodes.apply {
    repeat(length) { target.appendChild(item(it).cloneNode(true)) }
}