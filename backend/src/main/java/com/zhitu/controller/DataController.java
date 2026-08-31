package com.zhitu.controller;
import com.zhitu.common.ApiResponse;import com.zhitu.dto.Requests;import com.zhitu.service.DataGovernanceService;import jakarta.validation.Valid;import org.springframework.web.bind.annotation.*;import org.springframework.web.multipart.MultipartFile;import java.util.*;
@RestController @RequestMapping("/api/data") public class DataController {private final DataGovernanceService service;public DataController(DataGovernanceService s){service=s;}
 @PostMapping("/import/csv")public ApiResponse<Map<String,Object>>csv(@RequestParam MultipartFile file)throws Exception{return ApiResponse.ok("导入完成",service.importCsv(file));}
 @PostMapping("/upload")public ApiResponse<Map<String,Object>>upload(@RequestParam MultipartFile file,@RequestParam(defaultValue="REPORT")String sourceType)throws Exception{return ApiResponse.ok(service.upload(file,sourceType));}
 @PostMapping("/text")public ApiResponse<Map<String,Object>>text(@Valid @RequestBody Requests.TextImport r){return ApiResponse.ok(service.importText(r.sourceName(),r.sourceType(),r.sourceUrl(),r.content()));}
 @PostMapping("/url")public ApiResponse<Map<String,Object>>url(@Valid @RequestBody Requests.UrlImport r)throws Exception{return ApiResponse.ok("网页采集完成",service.importUrl(r.url(),r.sourceType()==null?"WEB":r.sourceType()));}
 @GetMapping("/documents")public ApiResponse<List<Map<String,Object>>>documents(@RequestParam(defaultValue="100")int limit){return ApiResponse.ok(service.documents(limit));}
 @GetMapping("/quality")public ApiResponse<Map<String,Object>>quality(){return ApiResponse.ok(service.qualitySummary());}}
