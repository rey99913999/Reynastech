# Custom tools: v1 definition format

This is the portable data contract for the custom-tool import work. It does not execute code by itself.

## Import behavior

- Imports are stored under the app-private `files/custom_tools` directory.
- An import is validated and always saved as disabled, even if the incoming JSON contains `"enabled": true`.
- Reusing an existing `id` requires explicit replacement.
- IDs and exported tool names are constrained to safe identifier characters; script source and JSON size are bounded.
- This repository is a foundation only: the import screen, permission review, runtime dispatch, and assistant-tool registration must be connected before imported tools become callable.

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

- `actions`: fixed sequence of existing Nastech tool names and JSON arguments, up to 32 steps.
- `javascript`: source intended for the existing JavaScript runtime.
- `python`: source intended to run through the existing Termux integration.
- `mcp`: wrapper for `mcpServerId` and `remoteToolName` referencing an already configured server.

## Execution safety requirements

- Never activate imported definitions automatically.
- Show the script/action contents and exact requested capabilities before enabling.
- Require explicit per-run approval for JavaScript/Python and any action with side effects.
- Run Python only through the existing Termux permission/verification path, with bounded time and output.
- Do not treat static validation as a sandbox; runtimes must still enforce time, memory, output, and tool-permission limits.
