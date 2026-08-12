package com.ep.knowledge.retrieval.rpc;

import com.ep.knowledge.api.agent.AgentChatCmd;
import com.ep.knowledge.api.agent.AgentChatResult;
import com.ep.knowledge.api.retrieval.*;
import com.ep.knowledge.retrieval.service.HybridSearchService;
import com.ep.knowledge.retrieval.service.RagService;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class RetrievalRpcServiceImpl implements RetrievalRpcService {

    private final HybridSearchService hybridSearchService;
    private final RagService ragService;

    public RetrievalRpcServiceImpl(HybridSearchService hybridSearchService, RagService ragService) {
        this.hybridSearchService = hybridSearchService;
        this.ragService = ragService;
    }

    @Override
    public SearchResult search(QueryCmd cmd) {
        return hybridSearchService.search(cmd);
    }

    @Override
    public RagResult ragChat(RagCmd cmd) {
        return ragService.rag(cmd);
    }
}
