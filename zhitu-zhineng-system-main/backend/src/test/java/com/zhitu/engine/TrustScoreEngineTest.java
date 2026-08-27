package com.zhitu.engine;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class TrustScoreEngineTest {@Test void moreEvidenceRaisesConfidence(){TrustScoreEngine e=new TrustScoreEngine();assertTrue(e.confidence(10,5,.9,.1,.9)>e.confidence(1,1,.5,.8,.4));}@Test void weakEvidenceRaisesRisk(){TrustScoreEngine e=new TrustScoreEngine();assertTrue(e.hallucinationRisk(1,1,.4,true)>.6);}}
