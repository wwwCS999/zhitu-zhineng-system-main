package com.zhitu.common;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class Jsons {
 private final ObjectMapper mapper; public Jsons(ObjectMapper mapper){this.mapper=mapper;}
 public String write(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("JSON序列化失败",e);}}
 public <T>T read(String json,Class<T> type){try{return mapper.readValue(json,type);}catch(Exception e){throw new IllegalArgumentException("JSON解析失败",e);}}
 public List<Map<String,Object>> listOfMaps(String json){try{return mapper.readValue(json,new TypeReference<>(){});}catch(Exception e){return List.of();}}
}
