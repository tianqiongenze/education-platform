package com.ep.knowledge.agent.entity;

import java.util.List;

public class Agent {

    private String id;
    private String tenantId;
    private String name;
    private String systemPrompt;
    private List<String> knowledgeBaseIds;
    private List<String> tools;
    /** ROUTER | PIPELINE | DEBATE */
    private String mode;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public List<String> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public void setKnowledgeBaseIds(List<String> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; }
    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
