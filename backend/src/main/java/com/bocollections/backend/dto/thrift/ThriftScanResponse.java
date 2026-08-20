package com.bocollections.backend.dto.thrift;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ThriftScanResponse {
    private List<ThriftItem> items;
}
