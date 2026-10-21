import { readFile, writeFile, copyFile } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

// Adapter for keycloakify-shadcn-starter v250004.0.24. Fail if upstream changes.
const root = process.argv[2];
if (!root) throw new Error("Usage: node patch-registration.mjs <theme-source-directory>");
const directory = resolve(root, "src/login/components/UserProfileFormFields");
const component = resolve(directory, "UserProfileFormFields.tsx");
let source = await readFile(component, "utf8");
function replaceOnce(before, after) {
    if (source.split(before).length !== 2) throw new Error("Unexpected upstream registration component");
    source = source.replace(before, after);
}
replaceOnce('import { Fragment, useEffect } from "react";',
    'import { Fragment, useEffect, useMemo } from "react";\nimport { requiredRegistrationContext } from "./registration-fields.mjs";');
replaceOnce('    const { kcContext } = useKcContext();',
    '    const { kcContext: originalContext } = useKcContext();\n    const kcContext = useMemo(() => requiredRegistrationContext(originalContext), [originalContext]);');
for (const filename of ["registration-fields.mjs", "registration-fields.d.mts"]) {
    await copyFile(resolve(dirname(fileURLToPath(import.meta.url)), filename), resolve(directory, filename));
}
await writeFile(component, source);
