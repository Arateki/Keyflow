# OpenAI — Sign in with ChatGPT (uso do plano em apps open-source): notas literais

**Conferido em 2026-09-29** lendo o **texto literal** (versões `.md` das páginas, `curl -4`), não o resumo automático da
primeira pesquisa. Páginas: `https://developers.openai.com/siwc/token-sharing-open-source` (visão geral) e `/sign-in`,
`/token-reference`, `/profiles-and-sessions`, `/models-and-inference`, `/errors-and-recovery`, `/preview-limitations`,
`/ui-ux-guidelines`; mais o documento de descoberta OIDC `https://auth.openai.com/.well-known/openid-configuration`.
Recurso em **prévia**: reconferir antes de publicar. Tudo o que o código usa está aqui com a fonte; o que **não** está
documentado está listado no fim.

## Respostas às 8 perguntas da RSN-T1

**(a) Renovação** — `profiles-and-sessions#refreshing-tokens`:
> "POST form-encoded `grant_type=refresh_token`, the issued `client_id` saved with this token set (not `dynamic_agent_client`), the saved `refresh_token`, and `resource=https://api.openai.com/v1` to `https://auth.openai.com/api/accounts/oauth/token`; omit `scope` to retain the grant."
> "Store and use the latest replacement, and serialize refreshes for the same session so two processes do not race a rotating token."
> `sign-in` §5: "Replace the access token, expiry, granted scopes, and rotating refresh token together after a successful refresh."

**(b) Revogação** — `profiles-and-sessions#end-the-renewable-session`:
> "revoke the renewable session using the `revocation_endpoint` from `https://auth.openai.com/.well-known/openid-configuration`. Send a form-encoded `POST` with `token=<REFRESH_TOKEN>`, `token_type_hint=refresh_token`, and the issued `client_id`. An empty HTTP `200` is the revocation success response, including for an already-invalid token."
> "For a network failure or `5xx`, retry with backoff while the refresh token is still available. If you finish signing out locally without confirming revocation, clear the local tokens and tell the user that remote revocation was not confirmed."

Lido do documento de descoberta (2026-09-29): `issuer=https://auth.openai.com`, `authorization_endpoint=https://auth.openai.com/api/accounts/authorize`,
`token_endpoint=https://auth.openai.com/api/accounts/oauth/token`, **`revocation_endpoint=https://auth.openai.com/api/accounts/oauth/revoke`**,
**`jwks_uri=https://auth.openai.com/.well-known/jwks.json`**, `id_token_signing_alg_values_supported=[RS256]`, `code_challenge_methods_supported=[S256]`,
`token_endpoint_auth_methods_supported=[client_secret_basic, client_secret_post, none]`. A doc manda usar a URL **do documento de descoberta**, não fixa.

**(c) `ext_agent_host_id`** — visão geral:
> "Generate and persist a stable `ext_agent_host_id` for each host separately. Choose and persist the host ID before its first sign-in. Use an opaque value, not an email, user ID, or other user-identifying value. A host ID is not an authentication credential."
> "prefer a JWK thumbprint URI … using RFC 9278 … `urn:ietf:params:oauth:jwk-thumbprint:…` … This recommendation is optional; UUID-based host IDs remain supported."
> "A public-key-derived host ID is currently an identifier only. OpenAI does not verify possession of the private key as part of this flow."
> "For a UUID-based host ID, generate a UUIDv4 once per host, prefix its hyphenated string form with `urn:uuid:`, and persist the value for reuse across sign-ins."
Formatos aceitos: `urn:ietf:params:oauth:jwk-thumbprint:…`, `urn:uuid:…`, `did:key:<key>`.

**(d) Validação do ID token** — `sign-in` §4:
> "Verify its signature against OpenAI's published JWKS. Check the issuer, audience against the issued client ID, expiration, and the nonce saved for this attempt. Use its validated `sub` as the account identity. Email and `sub` are not workspace identifiers; retain the issued client ID with the verified identity to keep registrations separate."
> "Check the token response's granted scopes for `chatgpt.tokens.use.direct` before proceeding to inference. A valid ID token alone does not authorize ChatGPT plan usage."
Access token é JWT com `aud=https://api.openai.com/v1`, `iss=https://auth.openai.com`, `client_id`, `scope`, `exp`… (token-reference).

**(e) `earliest_refresh_at`** — `token-reference`: só é listado como campo da resposta ("`access_token`, `refresh_token`, `id_token`, `token_type`, `expires_in`, `scope`, and `earliest_refresh_at`"). **Semântica e formato NÃO documentados.**

**(f) Corpo de erro do endpoint de token** — só os **códigos** estão documentados (`errors-and-recovery#refresh-errors`):
> "**Unusable refresh token:** `invalid_grant` on refresh, `invalid_refresh_token`, `token_expired`, `refresh_token_expired`, `refresh_token_invalidated`, or `refresh_token_reused`. Clear unusable tokens and repeat OAuth with the saved issued client ID." · "**Invalid client:** `invalid_client`. Fix the client configuration."
Forma exata do corpo **não documentada** (assumir JSON OAuth padrão com `error`; tolerar ausência).

**(g) `input` e papéis** — `models-and-inference` / `preview-limitations`:
> corpo de exemplo: `{"model": …, "input": [{"role": "user", "content": "…"}], "store": false, "stream": true}`
> "Use `instructions` or developer messages; explicit `{type: "message", role: "system"}` items are rejected."
> "Omit `background`, `conversation`, `max_output_tokens`, `max_tool_calls`, `metadata`, `moderation`, `multi_agent`, `prompt`, `prompt_cache_retention`, `safety_identifier`, `temperature`, `top_logprobs`, `top_p`, `truncation`, and `user`." · "Omit `previous_response_id` over HTTP."

**(h) App pago / anúncios** — visão geral: "These docs explain ChatGPT plan usage for open-source and locally hosted apps. If you're interested in offering it in a paid or remotely hosted app, complete the interest form." Nada sobre anúncios. As diretrizes de UI falam de apps com "own credits" e pedem "Distinguish ChatGPT plan usage from your app's own subscription or charges" — contexto de parceiros. **A elegibilidade do KeyFlow segue como decisão do Yan (RPRV-04).**

## Outros fatos que o código precisa

- **Autorização** `https://auth.openai.com/api/accounts/authorize` — parâmetros: `client_id` (`dynamic_agent_client` só no 1º login; depois o emitido), `agent_name_hint` (só no registro novo), `ext_agent_host_id`, `id_token_hint` e `login_hint` (só na reautorização), `response_type=code`, `redirect_uri`, `scope` (`openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`), `resource=https://api.openai.com/v1`, `state`, `nonce`, `code_challenge_method=S256`, `code_challenge`. Valores com URL-encode.
- **Redirect** — "HTTP loopback callback on `127.0.0.1` … Keep the scheme, host, and path unchanged: `/callback` does not match `/auth/callback`. … Do not substitute with `localhost`." **O caminho é o que o app escolhe no registro e não pode mudar depois** (exemplo da doc: `http://127.0.0.1:1455/auth/callback`; só a porta varia). Dentro de uma tentativa, a mesma URI exata na autorização e na troca do código.
- **Callback** — registro novo devolve `code`, `state` e `client_id` (`oaiapp_…`); "If a new-registration callback does not include an issued ID, treat registration as incomplete." Na reautorização o `client_id` pode faltar (usar o retido); "If the callback supplies a different client ID, reject the result". `error=access_denied` → validar `state`, parar, não trocar código. `invalid_grant` na troca → descartar o código e recomeçar.
- **Troca do código** — POST form para o `token_endpoint` com `grant_type=authorization_code`, `client_id` emitido, `code`, `code_verifier`, mesma `redirect_uri`, mesmo `resource`. Sem `client_secret`.
- **Tokens** — access 1 h (`expires_in: 3600`); refresh 30 dias, **rotativo** a cada renovação.
- **Plano não habilitado** — sem `chatgpt.tokens.use.direct` nos escopos concedidos: "retain the sign-in but mark ChatGPT plan usage as disabled" e oferecer a chave de API. Reabilitar: repetir o OAuth com o `client_id` salvo, o **conjunto completo de escopos** e `prompt=consent` ("Your app should not force consent on every ordinary sign-in").
- **Inferência** — `POST https://api.openai.com/v1/responses`, `Authorization: Bearer <access>`, `Content-Type: application/json`, **`store:false` e `stream:true` obrigatórios**; "do not point it at ChatGPT's `backend-api`". Eventos: `response.output_text.delta` (campo `delta`), `response.completed` (**só aqui é sucesso**), `response.failed` (`response.error.code`), `response.incomplete`.
- **Lista de modelos** — `GET https://api.openai.com/v1/models` com o mesmo token; resposta do **plano** tem `models[]` com `slug`, `display_name`, `visibility` (`"list"` = exibir), ordem do servidor preservada; mostrar `display_name` e enviar `slug` como `model`. (Com **chave de API** a lista é a padrão da OpenAI, `data[].id` — formato diferente; conferir ao implementar.)
- **Erros antes do stream** — `401` (identidade/permissão não aceita), `403` (política/região), `503` (rota indisponível; espera limitada); corpo pode ser `{"detail":"..."}` (texto diagnóstico, não código).
- **Códigos estruturados** (`error.code`, `error.param`): `subscription_sharing_user_not_eligible` 403 (não repetir, não ficar em loop de OAuth); `subscription_sharing_usage_limit_exceeded` 429 (pausar; link https://chatgpt.com/settings/usage); `subscription_sharing_usage_unavailable` 503 (espera limitada; pode vir em `response.failed` no meio do stream); `subscription_sharing_unsupported_capability` 400 (ver `error.param`, não repetir o corpo); `subscription_sharing_route_not_supported` 403; `subscription_sharing_invalid_user` 401 (pedir novo login após revogação ou erro terminal de refresh); `chatpass_v2_scope_not_authorized` / `chatpass_v2_invalid_authorization_context` 403; `subscription_sharing_user_unavailable` 503.
- **Desconexão** — a OpenAI não avisa; "Do not erase credentials solely because of a temporary network or infrastructure failure." "Preserve the actual HTTP status, response body shape, error code when present, and request ID."
- **Segurança** — tokens fora de URL, log, analytics; `id_token_hint` só para o endpoint de autorização e redigido em logs.
- **UI/UX (aprovadas)** — botão **"Continue with ChatGPT"**; ao usar o plano, mostrar **"Using ChatGPT plan"** com link **"Manage usage"** → https://chatgpt.com/settings/usage; modal só no 1º login com uso do plano: **"You're using your ChatGPT plan"** / **"Got it"**; limite de uso: **Manage usage** como ação primária; "Distinguish ChatGPT plan usage from your app's own subscription or charges". Plus: limite de cinco horas compartilhado entre todos os apps.

## Ainda NÃO documentado (não fabricar)
1. **Semântica/formato de `earliest_refresh_at`.**
2. **Forma exata do corpo de erro do endpoint de token** (só os códigos).
3. Se **anúncios/extras pagos** do app afastam o enquadramento *open-source/local* (RPRV-04).
4. Como o app **Android em segundo plano** se comporta com o listener de loopback (medir no A32 — RSN-T19).
5. Formato da lista de modelos para **chave de API** nesta base (conferir ao implementar a RSN-T15).
