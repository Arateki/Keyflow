Fixtures de renderização, sem pesos. Preservadas sem edição; licenças dos modelos em app/licenses/.
- gemma-e2b.jinja: seção LlmMetadata do arquivo gemma-4-E2B-it.litertlm, litert-community/gemma-4-E2B-it-litert-lm, commit b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1 (Apache-2.0, conforme licença vendorizada).
- qwen3-4b.jinja: seção LlmMetadata do arquivo qwen3_4b_instruct_2507_mixed_int4.litertlm, litert-community/Qwen3-4B-Instruct-2507, commit 4d409771b414d86b270ca54d27ffc45032a81142 (Apache-2.0).
- qwen2p5.jinja: seção LlmMetadata do arquivo Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm, litert-community/Qwen2.5-1.5B-Instruct, commit 2f23bacf8ec78b83dfcd7271c5cb25777b1be5e0 (Apache-2.0).
- qwen-legado.jinja: fallback de GetDefaultJinjaPromptTemplate (runtime/util/model_type_utils.cc, LiteRT-LM v0.16.1, Copyright 2025 The ODML Authors, Apache-2.0), expandido com os prefixos/sufixos do Qwen3-0.6B.litertlm, commit a3c5d805ae362dff7f580bc25f2dfb9a5a7eaa76.
Fontes: https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/runtime/util/model_type_utils.cc e https://huggingface.co/litert-community .
O teste usa o processador do Qwen fed81d1 para renderizar as fixtures; não executa inferência com Gemma/4B/2.5.
