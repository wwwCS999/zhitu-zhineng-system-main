package com.zhitu.engine;
import org.springframework.stereotype.Component;
@Component public class TrustScoreEngine {
 public double confidence(int evidenceCount,int sourceCount,double freshness,double duplicatePenalty,double semanticSupport){
  double evidence=Math.min(1,evidenceCount/8.0), sources=Math.min(1,sourceCount/4.0);
  return clamp(.25*evidence+.25*sources+.20*freshness+.20*semanticSupport+.10*(1-duplicatePenalty));
 }
 public double hallucinationRisk(int evidenceCount,int sourceCount,double confidence,boolean generatedOnly){
  double r=.55*(1-confidence)+(sourceCount<2?.2:0)+(evidenceCount<3?.15:0)+(generatedOnly?.25:0);return clamp(r);
 }
 private double clamp(double x){return Math.max(0,Math.min(1,x));}
}
