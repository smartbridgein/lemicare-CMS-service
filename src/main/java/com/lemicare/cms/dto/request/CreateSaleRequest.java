package com.lemicare.cms.dto.request;

import com.cosmicdoc.common.model.Sale;
import lombok.*;

import java.util.List;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class CreateSaleRequest {
    String orgId;
    String branchId;
    List<SaleItemDto> saleItemDtoList;
    Sale sale;
}
