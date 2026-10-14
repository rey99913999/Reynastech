# Custom tools: v1 definition format

This is the portable data contract for custom-tool import. The UI imports definitions disabled, and the runtime exposes only reviewed/enabled definitions as assistant-callable tools.

## Import behavior

- Imports are stored under the app-private `files/custom_tools` directory.
- An import is validated and always saved as disabled, even if the incoming JSON contains `"enabled": true`.
- Reusing an existing `id` requires explicit replacement.
- IDs and exported tool names are constrained to safe identifier characters; script source and JSON size are bounded.
- Imported tools are exposed to assistants as `ct_<name>` so they do not collide with built-in tool names. The UI shows the complete source/actions and requires an explicit review action before enabling.

## Definition shape

```json
{
  "id": "hello-python",
  "name": "hello_python",
  "description": "Print a greeting from Python.",
  "kind": "python",
  "inputSchema": {
    "type": "object",
    "properties": {
      "name": { "type": "string", "description": "Who to greet" }
    },
    "required": ["name"]
  },
  "source": "import json\nprint('Hello')",
  "enabled": false
}
```

Supported `kind` values:

- `actions`: fixed sequence of existing Nastech tool names and JSON arguments, up to 32 steps. v1 has no inter-step templating; every step uses the args embedded in the definition.
- `javascript`: source runs inside a bounded QuickJS context. Use the `input` object for arguments and `return` a result.
- `python`: source runs through the existing Termux RUN_COMMAND bridge. Arguments are exposed as an `input` dict; print output to return a result.
- `mcp`: wrapper for `mcpServerId` (UUID of a configured server) and `remoteToolName`.

## Execution safety requirements

- Never activate imported definitions automatically.
- Show the script/action contents and exact requested capabilities before enabling.
- Require explicit per-run approval for JavaScript/Python and any action with side effects.
- Run Python only through the existing Termux permission/verification path, with bounded time and output.
- Do not treat static validation as a sandbox; runtimes must still enforce time, memory, output, and tool-permission limits.
