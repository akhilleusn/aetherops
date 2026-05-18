package com.aetherops.postmortem.service;

import com.aetherops.postmortem.model.PostmortemContext;
import com.aetherops.postmortem.model.PostmortemDraftResult;

public interface PostmortemDraftGenerator {

    PostmortemDraftResult generate(PostmortemContext context);
}
