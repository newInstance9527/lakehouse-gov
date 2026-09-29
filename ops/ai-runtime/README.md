# AI 运行时联合包（波次 A）

> LiteLLM + Milvus 一键验收；门户接线见 `doc/AI平台能力-部署说明.md`。

## 启停

```bash
# 1) LiteLLM（dev2 或本机）
cd ../litellm && cp .env.example .env   # 填 KEY
docker compose up -d
./verify-litellm.sh

# 2) Milvus
cd ../milvus && cp .env.example .env
docker compose up -d
./verify-milvus.sh
./probe-vector.sh
```

或本目录：`./verify-all.sh`（探测两套服务）。

## 门户

```properties
lh.ai.enabled=true
lh.ai.litellm-url=http://<host>:4000
lh.ai.litellm-master-key=${LH_LITELLM_MASTER_KEY:}
# Milvus 见 lh.ai.milvus-* / 知识库配置
```

## 验收

- [ ] LiteLLM `/v1/models` 200  
- [ ] 门户模型巡检 / Copilot 试连非 soft-skip  
- [ ] Milvus health + 向量探针 OK  
- [ ] 知识库入库可向量化（有模型 Key）
