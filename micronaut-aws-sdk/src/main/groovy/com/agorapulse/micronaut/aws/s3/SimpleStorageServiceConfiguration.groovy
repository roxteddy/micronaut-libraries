package com.agorapulse.micronaut.aws.s3

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.sqs.AmazonSQS
import groovy.transform.CompileStatic
import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Requires

import javax.inject.Named
import javax.validation.constraints.NotEmpty

@Named('default')
@CompileStatic
@ConfigurationProperties('aws.s3')
@Requires(classes = AmazonS3)
class SimpleStorageServiceConfiguration {

    @NotEmpty String bucket = ''

}