package com.zhitu.common;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class TextUtilsTest {@Test void normalizesAndHashes(){assertEquals("java spring boot",TextUtils.normalize(" Java   Spring Boot "));assertEquals(TextUtils.sha256("A B"),TextUtils.sha256("a  b"));}@Test void detectsSimilarity(){assertTrue(TextUtils.jaccard("Java Spring MySQL Redis","Java Spring MySQL Docker")>.4);}@Test void jsonRoundTripLikeList(){String j=TextUtils.jsonArray(java.util.List.of("Java","RAG"));assertEquals(java.util.List.of("Java","RAG"),TextUtils.jsonList(j));}}
