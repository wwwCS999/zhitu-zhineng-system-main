package com.zhitu.engine;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class MatchingScoreEngine {
 public record Result(double overall,double skill,double internship,double project,double stack,double level,double education,List<String>matched,List<String>missing){}
 public Result score(Set<String> user,Map<String,Double> required,Map<String,Double> bonus,String projects,double years,String level,String education,int internships,int relevantInternships){
  Set<String> matched=new LinkedHashSet<>(),missing=new LinkedHashSet<>();double total=0,hit=0;
  for(var e:required.entrySet()){total+=e.getValue();if(user.contains(e.getKey())){hit+=e.getValue();matched.add(e.getKey());}else missing.add(e.getKey());}
  double skill=total==0?0:100*hit/total;double bonusHit=bonus.keySet().stream().filter(user::contains).count();skill=Math.min(100,skill+Math.min(8,bonusHit*2));
  double internship=Math.min(100,30+internships*15+relevantInternships*5);
  long projectHits=user.stream().filter(s->projects!=null&&projects.toLowerCase().contains(s.toLowerCase())).count();double project=Math.min(100,35+projectHits*10);
  long stacks=user.stream().map(x->x.split(" ")[0]).distinct().count();double stack=Math.min(100,45+stacks*3);
  double expected=level!=null&&level.contains("高")?5:level!=null&&level.contains("中")?3:1;double levelScore=Math.min(100,60+Math.min(years,expected)/expected*40);
  double edu=education==null?60:(education.contains("硕士")||education.contains("博士")?95:education.contains("本科")?88:72);
  double overall=.40*skill+.18*internship+.15*project+.12*stack+.09*levelScore+.06*edu;
  return new Result(round(overall),round(skill),round(internship),round(project),round(stack),round(levelScore),round(edu),List.copyOf(matched),List.copyOf(missing));
 }
 private double round(double x){return Math.round(x*10)/10.0;}
}
