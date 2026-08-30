# Content Operations

Agentown Content Operations turns a customer's verified field notes and photo references into an editable Naver Blog draft. Hunt News and `huntlab.app` are outside this flow and continue on their independent daily-report pipeline.

## Product flow

1. The signed-in owner enters the brand, topic, audience, source notes, evidence, Google Drive photo folder, photo order, and style notes.
2. Agentown uses its managed Codex connection by default. An owner may instead select an active OpenAI, Anthropic, or Google credential already stored by Agentown.
3. If the managed AI is unavailable, Agentown creates a safe template containing explicit placeholders. It does not invent field experience, prices, materials, schedules, or results.
4. The owner edits the draft and reviews its evidence, field/photo coverage, reading structure, style, and publication-safety checks.
5. Approval requires both evidence and photo-rights confirmations, a readiness score of at least 70, and no remaining placeholder.
6. An approved draft exposes separate title and rich-body copy buttons, followed by a link to Naver Blog. Agentown does not log in to Naver, paste into the editor, or publish on the user's behalf.

## Measurement boundary

`qualityScore` is a publication-readiness score. It measures input sufficiency, evidence linkage, field/photo coverage, readable structure, audience/style fit, and missing-placeholder safety. It is not an SEO rank prediction and must never be presented as a guarantee of Naver exposure.

Actual search outcomes require post-publication observations such as indexing status, impressions, clicks, search position, and engaged reading. Those outcomes are deliberately separate from draft quality.

## Security and ownership

- Every draft read and mutation is scoped by the authenticated owner ID.
- Personal provider secrets use the existing encrypted credential directory and are decrypted only for the provider request.
- Raw source notes and evidence are stored for editing but are not returned in the draft-list view.
- Generation is idempotent per owner and request key.
- Approval is recorded separately from AI generation.
