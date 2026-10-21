# OpenAPI schema anonymizer

Standalone Kotlin/JVM 17 program using Jackson. Open this folder as a Maven project in IntelliJ and run `AnonymizeOpenApiKt`, or use Maven from a terminal.

```powershell
mvn compile exec:java "-Dexec.args=C:/temp/original.json C:/temp/anonymous.json"
```

Without additional arguments all root `components.schemas` are selected. To select specific root components, append their original names:

```powershell
mvn compile exec:java "-Dexec.args=C:/temp/original.json C:/temp/anonymous.json A B C D"
```

The output must not already exist. All processing is local. Maven may download compiler/library dependencies, but the program does not send the input anywhere or fetch network references.

The program resolves `$ref` JSON pointers in the same document or other local JSON files, collects their schema targets into one document, and rewrites references to anonymous local component references. Repeated references and cycles retain a common target; references are deliberately not replaced with independent copies. File paths and original reference prefixes disappear from the output.

`Common.CodeOmschrijving` is retained when it is the referenced schema name, or the filename of a standalone root schema. Its references become `#/components/schemas/Common.CodeOmschrijving`, including the slash before the name. Two distinct targets with that retained name cause an error rather than being silently merged.

Properties, string literals and discriminator mapping labels are renamed consistently. Composition, requiredness, nullability, primitive types and standard formats are retained. Numeric/boolean literals and numeric constraints are retained, so inspect those for confidential values before sharing. Explicit discriminator mappings are rewritten. Implicit mappings based on original model names require a generation check after anonymization.

Descriptions, examples, custom extensions, patterns and unsupported metadata are omitted and reported by category. This can change generation behavior, so reproduce the unwanted interface locally with the anonymous file before sharing it. The extract does not retain endpoint schemas or generation options; use the same Fabrikt options yourself. Root components not selected and not reached through references are excluded, including unreferenced sibling schemas in external documents.

JSON only. `$id`, anchors, dynamic/recursive references and network references are rejected to avoid resolving them incorrectly. No original-to-anonymous mapping is saved. Structure itself can still be recognizable; inspect the output before sharing.
