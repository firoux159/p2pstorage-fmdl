#!/bin/sh

sftp -i p2pstorage.pem ec2-user@172.31.16.10:node/logs/c01-t2-small_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.27.28:node/logs/c02-t2-small_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.24.48:node/logs/c03-t2-medium_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.19.20:node/logs/c04-t2-medium_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.21.44:node/logs/c05-t2-large_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.42.48:node/logs/c06-t2-large_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.6.148:node/logs/c07-t3-small_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.21.20:node/logs/c08-t3-small_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.29.51:node/logs/c09-t3-medium_workload.csv
sftp -i p2pstorage.pem ec2-user@172.31.27.113:node/logs/c10-t3-medium_workload.csv


