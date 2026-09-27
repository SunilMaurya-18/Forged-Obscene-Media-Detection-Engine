package com.forgeddetect.service;

import com.forgeddetect.model.Decision;

/** Published after a moderator changes an item's decision; listeners run once the transaction commits. */
public record DecisionChangedEvent(Long analysisId, Decision previous, Decision current) {}
