package com.seeker.tms.biz.testgen.service;

import java.util.function.BiConsumer;

public interface DocumentParserService {

    String parseDocument(String url, String fileName, BiConsumer<Integer, String> progressCallback);

    String recognizeImage(byte[] imageBytes);

    String recognizeImage(byte[] imageBytes, String surroundingText);
}
