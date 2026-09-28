package com.forgeddetect.service;

/** Published when an upload's labelled copy (provenance manifest embedded) is handed back to the uploader. */
public record LabelledCopyIssued(String base64) {}
