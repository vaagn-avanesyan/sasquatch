#!/bin/bash

# Constants
GITHUB_ACCESS_TOKEN=${1:-$GITHUB_ACCESS_TOKEN}
GITHUB_API_URL_TEMPLATE="https://%s.github.com/repos/%s/%s?access_token=%s%s"
GITHUB_API_HOST="api"
GITHUB_UPLOAD_HOST="uploads"
BINARY_FILE_FILTER="*.aar"
JQ_COMMAND=jq
PUBLISH_VERSION="$(grep "versionName = '" versions.gradle | awk -F "[']" '{print $2}')"

# GitHub API endpoints
REQUEST_URL_TAG="$(printf $GITHUB_API_URL_TEMPLATE $GITHUB_API_HOST $BUILD_REPOSITORY_NAME 'git/tags' $GITHUB_ACCESS_TOKEN)"
REQUEST_REFERENCE_URL="$(printf $GITHUB_API_URL_TEMPLATE $GITHUB_API_HOST $BUILD_REPOSITORY_NAME 'git/refs' $GITHUB_ACCESS_TOKEN)"
REQUEST_RELEASE_URL="$(printf $GITHUB_API_URL_TEMPLATE $GITHUB_API_HOST $BUILD_REPOSITORY_NAME 'releases' $GITHUB_ACCESS_TOKEN)"
REQUEST_UPLOAD_URL_TEMPLATE="$(printf $GITHUB_API_URL_TEMPLATE $GITHUB_UPLOAD_HOST $BUILD_REPOSITORY_NAME 'releases/{id}/assets' $GITHUB_ACCESS_TOKEN '&name={filename}')"

# 1. Create a tag
echo "Create a tag ($PUBLISH_VERSION) for the commit ($BUILD_SOURCEVERSION)"
resp="$(curl -s -X POST $REQUEST_URL_TAG -d '{
    "tag": "'${PUBLISH_VERSION}'",
    "message": "'${PUBLISH_VERSION}'",
    "type": "commit",
    "object": "'${BUILD_SOURCEVERSION}'"
  }')"
sha="$(echo $resp | $JQ_COMMAND -r '.sha')"

# Exit if response doesn't contain "sha" key
if [ -z $sha ] || [ "$sha" == "" ] || [ "$sha" == "null" ]; then
  echo "Cannot create a tag"
  echo "Response:" $resp
  exit 1 
else
  echo "A tag has been created with SHA ($sha)"
fi

# 2. Create a reference
echo "Create a reference for the tag ($PUBLISH_VERSION)"
resp="$(curl -s -X POST $REQUEST_REFERENCE_URL -d '{
    "ref": "refs/tags/'${PUBLISH_VERSION}'",
    "sha": "'${sha}'"
  }')"
ref="$(echo $resp | $JQ_COMMAND -r '.ref')"

# Exit if response doesn't contain "ref" key
if [ -z $ref ] || [ "$ref" == "" ] || [ "$ref" == "null" ]; then
  echo "Cannot create a reference"
  echo "Response:" $resp
  exit 1 
else
  echo "A reference has been created to ${ref}"
fi

# 3. Create a release
echo "Create a release for the tag ($PUBLISH_VERSION)"
resp="$(curl -s -X POST $REQUEST_RELEASE_URL -d '{
    "tag_name": "'${PUBLISH_VERSION}'",
    "target_commitish": "master",
    "name": "'${PUBLISH_VERSION}'",
    "body": "Please update description. It will be pulled out automatically from release.md next time.",
    "draft": true,
    "prerelease": true
  }')"
id="$(echo $resp | $JQ_COMMAND -r '.id')"

# Exit if response doesn't contain "id" key
if [ -z $id ] || [ "$id" == "" ] || [ "$id" == "null" ]; then
  echo "Cannot create a release"
  echo "Response:" $resp
  exit 1 
else
  echo "A release has been created with ID ($id)"
fi

# 4. Copy binaries from :sdk
FILES="$(find sdk -name $BINARY_FILE_FILTER)"
for file in $FILES
do
  echo "Found binary" $file
  cp $file $BUILD_STAGINGDIRECTORY
done

# 5. Upload binaries
cd $BUILD_STAGINGDIRECTORY # This is required, file upload via curl doesn't properly work with absolute path

echo "Upload binaries"
uploadUrl="$(echo $REQUEST_UPLOAD_URL_TEMPLATE | sed 's/{id}/'$id'/g')"
totalCount=0
succeededCount=0
for file in $BINARY_FILE_FILTER
do
  totalCount=`expr $totalCount + 1`
  url="$(echo $uploadUrl | sed 's/{filename}/'${file}'/g')"
  resp="$(curl -s -X POST -H 'Content-Type: application/zip' --data-binary @${file} $url)"
  id="$(echo $resp | $JQ_COMMAND -r '.id')"

  # Log error if response doesn't contain "id" key
  if [ -z $id ] || [ "$id" == "" ] || [ "$id" == "null" ]; then
    echo "Cannot upload" $file
    echo "Request URL:" $url
    echo "Response:" $resp
  else
    succeededCount=`expr $succeededCount + 1`
    echo $file "Uploaded successfully"
  fi
done

# Exit if upload failed at least one binary
if [ $succeededCount -eq $totalCount ]; then
  echo $succeededCount "binaries have been uploaded successfully"
else
  echo "Failed to upload $(($totalCount-$succeededCount)) binaries."
  exit 1
fi