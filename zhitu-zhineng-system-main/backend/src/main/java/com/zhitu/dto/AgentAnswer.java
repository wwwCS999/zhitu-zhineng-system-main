package com.zhitu.dto;
import java.util.*;
public record AgentAnswer(String answer,List<String> agents,List<Map<String,Object>> evidence,List<String> suggestedActions,double confidence) {}
