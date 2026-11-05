package com.hmdp.ai;

import lombok.Data;

@Data
public class BlogDraftRequest {
    /** Optional shop the note is about; kept as a string so big ids survive JSON round-trips. */
    private String shopId;
    private String keywords;
    /** xiaohongshu | casual | concise */
    private String style;
}
