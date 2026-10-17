# public flavor source set

Public build = everything EXCEPT stealth (brief §6). This source set stays
empty unless a public-only override is ever needed. Shared code lives in
`src/main`. Stealth code must NEVER be added here or in `src/main`.
