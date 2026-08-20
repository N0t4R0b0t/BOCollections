package com.bocollections.backend.dto.thrift;

import com.bocollections.backend.entity.OwnedStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ThriftClassifyResponse {
    private OwnedStatus ownedStatus;
    private Long itemId;
}
