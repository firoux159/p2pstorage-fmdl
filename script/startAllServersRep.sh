#!/bin/sh

ssh -i p2pstorage.pem ec2-user@172.31.16.10 "sh -c 'cd node; nohup ./startServer.sh 0 3 c01-c5-4xlarge 172.31.16.10 59001 0 0 0 > /dev/null 2>&1 &'"
sleep 10
ssh -i p2pstorage.pem ec2-user@172.31.27.28 "sh -c 'cd node; nohup ./startServer.sh 0 3 c02-t2-small 172.31.27.28 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.24.48 "sh -c 'cd node; nohup ./startServer.sh 0 3 c03-t2-small 172.31.24.48 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.19.20 "sh -c 'cd node; nohup ./startServer.sh 0 3 c04-t2-small 172.31.19.20 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.21.44 "sh -c 'cd node; nohup ./startServer.sh 0 3 c05-t2-medium 172.31.21.44 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.42.48 "sh -c 'cd node; nohup ./startServer.sh 0 3 c06-t2-medium 172.31.42.48 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.6.148 "sh -c 'cd node; nohup ./startServer.sh 0 3 c07-t2-medium 172.31.6.148 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.21.20 "sh -c 'cd node; nohup ./startServer.sh 0 3 c08-c5-xlarge 172.31.21.20 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.29.51 "sh -c 'cd node; nohup ./startServer.sh 0 3 c09-c5-xlarge 172.31.29.51 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.27.113 "sh -c 'cd node; nohup ./startServer.sh 0 3 c10-c5-2xlarge 172.31.27.113 59001 c01-c5-4xlarge 172.31.16.10 59001 > /dev/null 2>&1 &'"
sleep 10

