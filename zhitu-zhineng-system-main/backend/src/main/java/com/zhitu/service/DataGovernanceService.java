package com.zhitu.service;
import com.zhitu.common.TextUtils;
import com.zhitu.repository.Store;
import org.apache.commons.csv.*;
import org.apache.tika.Tika;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;
@Service
public class DataGovernanceService {
 private final Store store; private final Tika tika=new Tika();
 public DataGovernanceService(Store store){this.store=store;}
 public Map<String,Object> importText(String sourceName,String sourceType,String sourceUrl,String content){
  String cleaned=clean(content);if(cleaned.isBlank())throw new IllegalArgumentException("数据内容为空");
  String normalizedSource=safe(sourceName,"手工文本");String hash=TextUtils.sha256(cleaned);
  Optional<Map<String,Object>>already=store.maybe("SELECT * FROM source_document WHERE content_hash=:h AND source_name=:n ORDER BY id LIMIT 1",Map.of("h",hash,"n",normalizedSource));
  if(already.isPresent())return Map.of("documentId",already.get().get("id"),"duplicate",true,"alreadyImported",true,"qualityScore",already.get().get("quality_score"));
  Optional<Map<String,Object>>same=store.maybe("SELECT * FROM source_document WHERE content_hash=:h ORDER BY id LIMIT 1",Map.of("h",hash));
  double quality=quality(cleaned);double stale=sourceType!=null&&sourceType.equalsIgnoreCase("JD")?.12:.08;
  String group=same.map(x->x.get("duplicate_group")==null?"DG-"+x.get("id"):String.valueOf(x.get("duplicate_group"))).orElseGet(()->findNearDuplicate(cleaned));
  boolean duplicate=group!=null;String status=quality<.55?"LOW_QUALITY":duplicate?"DUPLICATE_REVIEW":"CLEANED";
  long id=store.insert("INSERT INTO source_document(source_type,source_name,source_url,content,content_hash,quality_score,stale_score,duplicate_group,status) VALUES(:t,:n,:u,:c,:h,:q,:s,:g,:st)",params("t",sourceType,"n",normalizedSource,"u",sourceUrl,"c",cleaned,"h",hash,"q",quality,"s",stale,"g",group,"st",status));
  return Map.of("documentId",id,"duplicate",duplicate,"alreadyImported",false,"qualityScore",quality,"staleScore",stale,"duplicateGroup",group==null?"":group);
 }
 public Map<String,Object> upload(MultipartFile file,String sourceType)throws Exception{
  if(file==null||file.isEmpty())throw new IllegalArgumentException("文件为空");String name=Optional.ofNullable(file.getOriginalFilename()).orElse("upload.bin");String text=tika.parseToString(file.getInputStream());return importText(name,sourceType,null,text);
 }
 public Map<String,Object> importUrl(String url,String sourceType)throws Exception{
  URI uri;try{uri=URI.create(url);}catch(Exception e){throw new IllegalArgumentException("URL 格式不正确");}
  if(!Set.of("http","https").contains(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT))||uri.getHost()==null)throw new IllegalArgumentException("仅支持 http/https URL");
  InetAddress address=InetAddress.getByName(uri.getHost());if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isLinkLocalAddress())throw new IllegalArgumentException("不允许访问本机或内网地址");
  Document page=Jsoup.connect(url).userAgent("Mozilla/5.0 ZhituCareerGraph/1.0").timeout(15000).followRedirects(true).maxBodySize(5*1024*1024).get();
  String title=page.title().isBlank()?uri.getHost():page.title();String text=page.body()==null?page.text():page.body().text();Map<String,Object>result=new LinkedHashMap<>(importText(uri.getHost()+"|"+title,sourceType,url,text));result.put("title",title);result.put("url",url);return result;
 }
 public Map<String,Object> importCsv(MultipartFile file)throws Exception{return importCsv(file.getInputStream(),Optional.ofNullable(file.getOriginalFilename()).orElse("jobs.csv"));}
 public Map<String,Object> importCsv(Path path)throws Exception{try(InputStream in=Files.newInputStream(path)){return importCsv(in,path.getFileName().toString());}}
 private Map<String,Object> importCsv(InputStream input,String source)throws Exception{
  int inserted=0,duplicates=0,invalid=0;Reader reader=new InputStreamReader(input,StandardCharsets.UTF_8);
  CSVFormat fmt=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true).get();
  for(CSVRecord r:fmt.parse(reader))try{
   String title=get(r,"招聘岗位","岗位名称","job_title");String company=get(r,"企业名称","公司名称","company");String desc=get(r,"职位描述","岗位描述","description");if(title.isBlank()||desc.isBlank()){invalid++;continue;}
   String sourcePlatform=get(r,"来源平台","来源","source");String sourceKey=safe(sourcePlatform,"未知来源")+"|"+company+"|"+title;Map<String,Object>d=importText(sourceKey,"JD",null,title+"\n"+desc);
   if(Boolean.TRUE.equals(d.get("alreadyImported"))){duplicates++;continue;}if(Boolean.TRUE.equals(d.get("duplicate")))duplicates++;long doc=((Number)d.get("documentId")).longValue();
   Integer min=intVal(get(r,"最低月薪","salary_min")),max=intVal(get(r,"最高月薪","salary_max"));LocalDate date=dateVal(get(r,"招聘发布日期","发布日期","posted_at"));
   store.insert("INSERT INTO job_posting(document_id,job_title,company,city,level_name,tech_stack,salary_min,salary_max,description,posted_at,parsed) VALUES(:d,:t,:c,:city,:l,:s,:min,:max,:x,:p,false)",params("d",doc,"t",title,"c",company,"city",get(r,"工作城市","城市","city"),"l",inferLevel(title+desc),"s",inferStack(title+desc),"min",min,"max",max,"x",desc,"p",date));inserted++;
  }catch(Exception e){invalid++;}
  return Map.of("source",source,"inserted",inserted,"duplicates",duplicates,"invalid",invalid);
 }
 public List<Map<String,Object>> documents(int limit){return store.list("SELECT id,source_type,source_name,quality_score,stale_score,duplicate_group,status,created_at FROM source_document ORDER BY id DESC LIMIT :n",Map.of("n",Math.min(500,Math.max(1,limit))));}
 public Map<String,Object> qualitySummary(){return store.one("SELECT COUNT(*) total,AVG(quality_score) avg_quality,AVG(stale_score) avg_stale,SUM(CASE WHEN duplicate_group IS NOT NULL THEN 1 ELSE 0 END) near_duplicates,SUM(CASE WHEN status='LOW_QUALITY' THEN 1 ELSE 0 END) low_quality FROM source_document",Map.of());}
 public String clean(String text){
  if(text==null)return "";
  String x=text.replaceAll("(?is)<script.*?</script>|<style.*?</style>"," ")
      .replaceAll("(?is)<[^>]+>"," ")
      .replaceAll("招聘热线[:：]?\\S+|点击申请|立即沟通|福利待遇[:：]?"," ");
  return x.replaceAll("[\\t\\r]+"," ").replaceAll("[ ]{2,}"," ").replaceAll("\\n{3,}","\\n\\n").trim();
 }
 private String findNearDuplicate(String text){List<Map<String,Object>>recent=store.list("SELECT id,content,duplicate_group FROM source_document ORDER BY id DESC LIMIT 200",Map.of());for(Map<String,Object>r:recent){if(TextUtils.jaccard(text,String.valueOf(r.get("content")))>=.82)return r.get("duplicate_group")==null?"DG-"+r.get("id"):String.valueOf(r.get("duplicate_group"));}return null;}
 private double quality(String x){double len=Math.min(1,x.length()/500.0),structure=(x.contains("职责")||x.contains("要求"))?.2:.05,skill=(x.matches("(?is).*(java|python|rag|spring|mysql|大模型|物联网|flink|docker).*"))?.25:.05;return Math.round(Math.min(1,.3+.25*len+structure+skill)*1000)/1000.0;}
 private String inferLevel(String x){if(x.matches("(?is).*(高级|资深|专家|架构).*"))return "高级";if(x.matches("(?is).*(中级|3-5年|三年以上).*"))return "中级";return "初级";}
 private String inferStack(String x){String n=x.toLowerCase();if(n.matches("(?s).*(大模型|rag|agent|langchain|prompt).*"))return "大模型应用";if(n.matches("(?s).*(java|spring|微服务).*"))return "后端开发";if(n.matches("(?s).*(flink|spark|hadoop|数仓).*"))return "大数据";if(n.matches("(?s).*(物联网|嵌入式|mqtt|边缘).*"))return "物联网";if(n.matches("(?s).*(视觉|机器人|智能系统).*"))return "智能系统";return "人工智能";}
 private String get(CSVRecord r,String...names){for(String n:names)try{if(r.isMapped(n)&&r.get(n)!=null)return r.get(n).trim();}catch(Exception ignored){}return "";}
 private Integer intVal(String s){try{return s.isBlank()?null:(int)Double.parseDouble(s.replaceAll("[^0-9.]",""));}catch(Exception e){return null;}}
 private LocalDate dateVal(String s){try{return s.isBlank()?LocalDate.now().minusDays(new Random().nextInt(180)):LocalDate.parse(s.substring(0,10));}catch(Exception e){return LocalDate.now().minusDays(new Random().nextInt(180));}}
 private String safe(String v,String d){return v==null||v.isBlank()?d:v;}
 private Map<String,Object> params(Object...v){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put(String.valueOf(v[i]),v[i+1]);return m;}
}
