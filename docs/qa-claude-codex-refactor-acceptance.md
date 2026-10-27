# Claude/Codex Q&A Refactor Acceptance

Date: 2026-08-19

## Scope

This refactor improves Q&A behavior for Claude and Codex without changing Agent mode permissions. It separates persisted conversation history from request-only project and attachment context, adds provider-native image requests, bounds input context, hardens streaming errors, and restricts Q&A project tools to read-only operations.

## Acceptance Matrix

| ID | Requirement | Code evidence | Automated evidence |
|---|---|---|---|
| QA-01 | Normal Q&A does not implicitly scan the project | `ChatPanel.buildQaSystemPrompt`, `ChatPanel.respondDirectly` | Diff inspection |
| QA-02 | Full scan performs one bounded search | `ChatPanel.sendQAScanMessage`, `SearchCoordinator.search` | Diff inspection |
| QA-03 | Claude/Codex full scan can use only local read-only tools | `ToolUseEngine`, `LlmProviderCapabilities.readOnlyTools` | Diff inspection |
| QA-04 | Attachments, source context, and scan results are request-local | `QaRequestComposer`, `ChatPanel.composeQaRequest` | `QaRequestComposerTest` |
| QA-05 | Recent complete turns and the current question survive budget trimming | `QaRequestComposer` | `QaRequestComposerTest` |
| QA-06 | Claude receives Anthropic image content blocks | `DeepSeekApiClient.buildAnthropicBody` | `ProviderRequestBodyTest` |
| QA-07 | Codex receives `input_image` and reasoning effort without `temperature` | `DeepSeekApiClient.buildCodexResponsesBody` | `ProviderRequestBodyTest` |
| QA-08 | Old sessions with no structured parts remain readable | Nullable `ChatMessage.parts` and `orEmpty()` consumers | `QaRequestComposerTest` plus compile |
| QA-09 | Streaming state exists before the HTTP source is attached | `DeferredEventSource`, `ChatPanel.respondDirectly` | `DeferredEventSourceTest` |
| QA-10 | Empty, failed, incomplete, malformed, and prematurely closed streams fail explicitly | Anthropic and Codex stream handlers in `DeepSeekApiClient` | Request-body unit tests plus code inspection |
| QA-11 | One retry is allowed only before the first token for transient failures | `ChatPanel.isRetryableQaError`, `ChatPanel.respondDirectly` | Code inspection |
| QA-12 | Partial answers are retained when a later stream error occurs | `ChatPanel.respondDirectly` error callback | Code inspection; endpoint scenario pending |
| QA-13 | Completed answers keep the full Token row visible when the user follows the bottom | `ChatAutoScrollController`, `ChatPanel.scrollToBottom` | `ChatAutoScrollControllerTest` plus UI code inspection |
| QA-14 | Token usage survives session redraws and old sessions remain compatible | `ChatMessage.usage`, `ChatPanel.renderMessageRange` | `ChatMessageUsageTest` |

## Automated Verification

```powershell
.\gradlew.bat test --stacktrace
git diff --check
```

Expected focused suites:

- `ProviderRequestBodyTest`: Anthropic and Codex payload contracts
- `QaRequestComposerTest`: transient context, history trimming, native image isolation
- `DeferredEventSourceTest`: cancellation and retry source replacement
- Existing attachment and CLI tests

## Manual Provider Scenarios

1. Send PNG, JPEG, and WebP images to Claude and Codex in Q&A mode and verify the answer uses image details.
2. Upload PDF, DOCX, XLSX, and PPTX files, ask a document-specific question, then ask an unrelated follow-up and verify document text does not leak into the next request.
3. Compare normal Q&A with full scan: normal Q&A must not show project scanning; full scan may use only `grep`, `glob`, and `read`.
4. Trigger HTTP 429 or a transient 5xx before the first token and verify one retry; trigger an error after output starts and verify no retry and preservation of partial output.
5. Load a conversation saved before version 2.7.5 and send a follow-up to verify nullable structured parts remain compatible.

## Residual Risk

Automated tests validate local composition and lifecycle behavior but do not call live Anthropic, Codex, or compatible proxy endpoints. Provider-specific proxy differences, image size limits, OAuth state, and real SSE timing still require the manual scenarios above.
