package com.zhitu.dto;
import java.util.*;
public record JobExtraction(String roleName,String techStack,String level,List<String> responsibilities,List<String> requiredSkills,List<String> bonusSkills,List<String> scenarios,double confidence,Map<String,Object> rationale) {}
