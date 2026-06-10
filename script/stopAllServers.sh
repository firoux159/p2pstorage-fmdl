#!/bin/sh

ssh -i p2pstorage.pem ec2-user@172.31.16.10 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.27.28 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.24.48 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.19.20 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.21.44 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.42.48 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.6.148 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.21.20 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.29.51 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"
ssh -i p2pstorage.pem ec2-user@172.31.27.113 "sh -c 'cd node; nohup ./stopServer.sh > /dev/null 2>&1 &'"


