package com.zhitu.engine;
import org.springframework.stereotype.Component;
@Component public class EmergingRoleScoringEngine {
 public double novelty(double titleNovelty,double skillNovelty,double growth,int sourceCount,int samples){return clamp(.3*titleNovelty+.25*skillNovelty+.25*growth+.1*Math.min(1,sourceCount/4.0)+.1*Math.min(1,samples/12.0));}
 public boolean qualifies(double novelty,double confidence,int sources,int samples){return novelty>=.52&&confidence>=.55&&sources>=2&&samples>=3;}
 private double clamp(double x){return Math.max(0,Math.min(1,x));}
}
