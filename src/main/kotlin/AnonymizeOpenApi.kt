import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

private val mapper = ObjectMapper()
private data class Target(val file: Path, val pointer: String)

class SchemaAnonymizer {
    private val documents = linkedMapOf<Path, JsonNode>()
    private val models = linkedMapOf<Target, String>()
    private val fields = linkedMapOf<String, String>()
    private val strings = linkedMapOf<String, String>()
    private val pending = ArrayDeque<Target>()
    val omitted = sortedSetOf<String>()

    private fun field(value: String) = fields.getOrPut(value) { "field${fields.size + 1}" }
    private fun text(value: String) = strings.getOrPut(value) { "value${strings.size + 1}" }
    private fun escape(value: String) = value.replace("~", "~0").replace("/", "~1")

    private fun document(file: Path): JsonNode = documents.getOrPut(file) {
        mapper.readTree(file.toFile()).also { root ->
            // Resource IDs and anchors need JSON Schema resolution, not filesystem resolution.
            fun check(node: JsonNode) {
                if (node.isObject) {
                    require(listOf("\$id", "\$anchor", "\$dynamicAnchor", "\$dynamicRef", "\$recursiveRef").none(node::has))
                    node.elements().forEachRemaining(::check)
                } else if (node.isArray) node.forEach(::check)
            }
            check(root)
        }
    }

    private fun reference(from: Path, raw: String): Target {
        val filePart = raw.substringBefore('#')
        val fragment = raw.substringAfter('#', "")
        val file = when {
            filePart.isEmpty() -> from
            filePart.startsWith("file:") -> Path.of(URI(filePart))
            else -> {
                require(!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(filePart))
                from.parent.resolve(URLDecoder.decode(filePart.replace("+", "%2B"), StandardCharsets.UTF_8))
            }
        }.toRealPath()
        val pointer = URLDecoder.decode(fragment.replace("+", "%2B"), StandardCharsets.UTF_8)
        require(pointer.isEmpty() || pointer.startsWith('/'))
        val target = Target(file, pointer)
        require(!document(file).at(pointer).isMissingNode)
        return target
    }

    private fun model(target: Target): String = models.getOrPut(target) {
        val originalName = target.pointer.substringAfterLast('/').replace("~1", "/").replace("~0", "~")
        val keepName = originalName == "Common.CodeOmschrijving" ||
            (target.pointer.isEmpty() && target.file.fileName.toString().removeSuffix(".json") == "Common.CodeOmschrijving")
        require(!keepName || "Common.CodeOmschrijving" !in models.values)
        val name = if (keepName) "Common.CodeOmschrijving" else "Model${models.size + 1}"
        pending.addLast(target)
        name
    }

    private fun ref(target: Target) = "#/components/schemas/${model(target)}"

    private fun literal(node: JsonNode): JsonNode = when {
        node.isTextual -> mapper.nodeFactory.textNode(text(node.textValue()))
        node.isArray -> mapper.createArrayNode().also { out -> node.forEach { out.add(literal(it)) } }
        node.isObject -> mapper.createObjectNode().also { out -> node.fields().forEachRemaining { (key, value) -> out.set<JsonNode>(field(key), literal(value)) } }
        else -> node.deepCopy<JsonNode>()
    }

    private fun clean(node: JsonNode, file: Path): JsonNode {
        if (node.isBoolean) return node
        require(node.isObject)
        val out = mapper.createObjectNode()
        node.fields().forEachRemaining { (key, value) ->
            when (key) {
                "\$ref" -> out.put(key, ref(reference(file, value.textValue())))
                "type" -> {
                    val types = if (value.isArray) value.toList() else listOf(value)
                    require(types.all { it.isTextual && it.textValue() in setOf("object", "array", "string", "integer", "number", "boolean", "null") })
                    out.set<JsonNode>(key, value)
                }
                "nullable", "readOnly", "writeOnly", "deprecated", "uniqueItems" -> {
                    require(value.isBoolean); out.set<JsonNode>(key, value)
                }
                "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
                "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties", "minContains", "maxContains" -> {
                    require(value.isNumber || value.isBoolean); out.set<JsonNode>(key, value)
                }
                "format" -> if (value.textValue() in setOf("int32", "int64", "float", "double", "byte", "binary", "date", "date-time", "time", "duration", "uuid", "uri", "uri-reference", "email", "hostname", "ipv4", "ipv6", "password")) out.set<JsonNode>(key, value) else omitted.add("custom format")
                "properties", "dependentSchemas" -> {
                    require(value.isObject)
                    val children = out.putObject(key)
                    value.fields().forEachRemaining { (name, child) -> children.set<JsonNode>(field(name), clean(child, file)) }
                }
                "required" -> {
                    require(value.isArray)
                    val required = out.putArray(key)
                    value.forEach { require(it.isTextual); required.add(field(it.textValue())) }
                }
                "dependentRequired" -> {
                    val dependencies = out.putObject(key)
                    value.fields().forEachRemaining { (name, entries) ->
                        val array = dependencies.putArray(field(name))
                        entries.forEach { array.add(field(it.textValue())) }
                    }
                }
                "allOf", "oneOf", "anyOf", "prefixItems" -> {
                    require(value.isArray)
                    val array = out.putArray(key)
                    value.forEach { array.add(clean(it, file)) }
                }
                "items", "additionalProperties", "unevaluatedProperties", "unevaluatedItems", "contains", "not", "if", "then", "else", "propertyNames" -> out.set<JsonNode>(key, clean(value, file))
                "enum", "const", "default" -> out.set<JsonNode>(key, literal(value))
                "discriminator" -> {
                    val discriminator = out.putObject(key)
                    discriminator.put("propertyName", field(value.required("propertyName").textValue()))
                    if (value.has("mapping")) {
                        val mapping = discriminator.putObject("mapping")
                        value["mapping"].fields().forEachRemaining { (label, destination) ->
                            val raw = destination.textValue()
                            val localName = document(file).path("components").path("schemas").has(raw)
                            val target = if (localName) Target(file, "/components/schemas/${escape(raw)}") else reference(file, raw)
                            mapping.put(text(label), ref(target))
                        }
                    } else omitted.add("implicit discriminator names require verification")
                }
                else -> omitted.add(if (key in setOf("title", "description", "example", "examples", "pattern", "patternProperties", "\$defs", "definitions", "\$schema")) key else "extensions/other metadata")
            }
        }
        return out
    }

    fun extract(input: Path, selected: List<String>): ObjectNode {
        require(documents.isEmpty())
        val file = input.toRealPath()
        val root = document(file)
        val schemas = root.path("components").path("schemas")
        require(schemas.isObject)
        val selection = selected.ifEmpty { schemas.fieldNames().asSequence().toList() }
        require(selection.isNotEmpty())
        selection.forEach { require(schemas.has(it)); model(Target(file, "/components/schemas/${escape(it)}")) }
        val out = mapper.createObjectNode()
        val version = root.path("openapi").asText()
        require(Regex("3\\.[012]\\.[0-9]+").matches(version))
        out.put("openapi", version)
        out.putObject("info").put("title", "Anonymous reproduction").put("version", "1")
        out.putObject("paths")
        val outputSchemas = out.putObject("components").putObject("schemas")
        while (pending.isNotEmpty()) {
            val target = pending.removeFirst()
            outputSchemas.set<JsonNode>(models.getValue(target), clean(document(target.file).at(target.pointer), target.file))
        }
        return out
    }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: AnonymizeOpenApi input.json output.json [SchemaA SchemaB ...]")
        exitProcess(1)
    }
    try {
        val anonymizer = SchemaAnonymizer()
        val output = anonymizer.extract(Path.of(args[0]), args.drop(2))
        Files.writeString(Path.of(args[1]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output) + "\n", StandardOpenOption.CREATE_NEW)
        println("Created anonymized JSON. Original files are unchanged. No name mapping was saved.")
        if (anonymizer.omitted.isNotEmpty()) println("Omitted/verify: " + anonymizer.omitted.joinToString())
    } catch (_: Exception) {
        System.err.println("Could not complete extraction. Check JSON input, schema names, local file refs and a new output filename. Anchors, resource IDs and network refs are unsupported. No original details are logged.")
        exitProcess(1)
    }
}
