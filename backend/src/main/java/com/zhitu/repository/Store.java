package com.zhitu.repository;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;
@Repository
public class Store {
 private final NamedParameterJdbcTemplate jdbc;
 public Store(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
 public List<Map<String,Object>> list(String sql,Map<String,?> p){return jdbc.queryForList(sql,p);}
 public Map<String,Object> one(String sql,Map<String,?> p){List<Map<String,Object>>r=list(sql,p);if(r.isEmpty())throw new IllegalArgumentException("记录不存在");return r.get(0);}
 public Optional<Map<String,Object>> maybe(String sql,Map<String,?> p){List<Map<String,Object>>r=list(sql,p);return r.stream().findFirst();}
 public int update(String sql,Map<String,?> p){return jdbc.update(sql,p);}
 public long insert(String sql,Map<String,?> p){KeyHolder kh=new GeneratedKeyHolder();jdbc.update(sql,new MapSqlParameterSource(p),kh,new String[]{"id"});Number n=kh.getKey();return n==null?0:n.longValue();}
 public long count(String table){return jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
 public double scalarDouble(String sql,Map<String,?>p){Double v=jdbc.queryForObject(sql,p,Double.class);return v==null?0:v;}
 public NamedParameterJdbcTemplate jdbc(){return jdbc;}
}
