
[![Build Status](https://travis-ci.org/Netflix/AWSObjectMapper.svg)](https://travis-ci.org/Netflix/AWSObjectMapper/builds)

# AWS ObjectMapper

Mapper that can be used with jackson to convert AWS model objects to/from json.

# DEPRECATED

The AWS SDK for Java V1 will no longer be supported after 2025 ([announcement]). This library is
not needed with the V2 SDK. You can [serialize]/[deserialize] using the builders and in the V2 SDK
they do not have conflicting setters causing ambiguity for Jackson ([#122]).

[announcement]: https://aws.amazon.com/blogs/developer/announcing-end-of-support-for-aws-sdk-for-java-v1-x-on-december-31-2025/
[#122]: https://github.com/aws/aws-sdk-java-v2/issues/122
[serialize]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-serialization-changes.html
[deserialize]: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/migration-deserialization-changes.html
