package com.zhitu.dto;
import java.util.*;
public record ResumeExtraction(String personName,List<String> skills,List<String> projects,String education,double experienceYears,double confidence,Map<String,Object> details) {}
