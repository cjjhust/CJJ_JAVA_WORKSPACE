package com.aslp.route.service;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class TrackingService {

    private final RestTemplate restTemplate = new RestTemplate();

    public String trackDhl(String trackingNumber) {
        // 模拟 DHL API 调用（实际应替换为真实 DHL/DPD 端点）
        String url = "https://api.dhl.com/track?number=" + trackingNumber;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return response.getBody();
    }

    public String trackDpd(String trackingNumber) {
        String url = "https://api.dpd.com/track?ref=" + trackingNumber;
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        return response.getBody();
    }
}
