package com.zhitu.engine;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class MatchingScoreEngineTest {@Test void fullSkillsScoreHigher(){MatchingScoreEngine e=new MatchingScoreEngine();Map<String,Double>req=Map.of("Java",1.0,"Spring Boot",.8);var full=e.score(Set.of("Java","Spring Boot"),req,Map.of(),"Java项目",2,"中级","本科",2,1);var partial=e.score(Set.of("Java"),req,Map.of(),"",1,"中级","本科",0,0);assertTrue(full.overall()>partial.overall());assertTrue(partial.missing().contains("Spring Boot"));}}
