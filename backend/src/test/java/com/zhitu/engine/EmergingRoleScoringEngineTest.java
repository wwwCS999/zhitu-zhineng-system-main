package com.zhitu.engine;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class EmergingRoleScoringEngineTest {@Test void qualifiedNeedsSources(){EmergingRoleScoringEngine e=new EmergingRoleScoringEngine();double n=e.novelty(.9,.8,.8,4,12);assertTrue(e.qualifies(n,.8,4,12));assertFalse(e.qualifies(n,.8,1,12));}}
