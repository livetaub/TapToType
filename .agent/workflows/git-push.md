---
description: Git commit and push workflow
---

## Rules

1. **Never push to git automatically.** Only commit and push after the user explicitly confirms the changes are accepted and ready to push.
2. When changes are ready for review, summarize what was changed and ask the user if they want to push.
3. Use separate `git add` and `git commit` commands (PowerShell doesn't support `&&`).
4. Keep commit messages concise and descriptive.
