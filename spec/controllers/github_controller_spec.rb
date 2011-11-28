require 'spec_helper'
require 'json'

sample = JSON.generate({
                         "before" => "5aef35982fb2d34e9d9d4502f6ede1072793222d",
                         "repository" => {
                           "url" => "http =>//github.com/defunkt/github",
                           "name" => "github",
                           "description" => "You're lookin' at it.",
                           "watchers" => 5,
                           "forks" => 2,
                           "private" => 1,
                           "owner" => {
                             "email" => "chris@ozmm.org",
                             "name" => "defunkt"
                           }
                         },
                         "commits" => [
                                       {
                                         "id" => "41a212ee83ca127e3c8cf465891ab7216a705f59",
                                         "url" => "http =>//github.com/defunkt/github/commit/41a212ee83ca127e3c8cf465891ab7216a705f59",
                                         "author" => {
                                           "email" => "chris@ozmm.org",
                                           "name" => "Chris Wanstrath"
                                         },
                                         "message" => "okay i give in",
                                         "timestamp" => "2008-02-15T14 =>57 =>17-08 =>00",
                                         "added" => ["filepath.rb"]
                                       },
                                       {
                                         "id" => "de8251ff97ee194a289832576287d6f8ad74e3d0",
                                         "url" => "http =>//github.com/defunkt/github/commit/de8251ff97ee194a289832576287d6f8ad74e3d0",
                                         "author" => {
                                           "email" => "chris@ozmm.org",
                                           "name" => "Chris Wanstrath"
                                         },
                                         "message" => "update pricing a tad",
                                         "timestamp" => "2008-02-15T14 =>36 =>34-08 =>00"
                                       }],
                         "after" => "de8251ff97ee194a289832576287d6f8ad74e3d0",
                         "ref" => "refs/heads/master"
                       })


describe GithubController do

  it "checks the json has a URL" do
    post :create, :payload => sample
    response.should be_success
    response.body.should == " " # rails render :nothing returns a doc of length 1, for some reason
  end

  it "should should give a dummy url that puts something in the DB so we can test that clojure does actually do something" do
    pending
  end

  it "should fail due to invalid json" do
    lambda {
      post :create, :payload => "invalid json"
    }.should raise_error
  end

  it "should fail due to empty json" do
    lambda {
      post :create, :payload => JSON.generate({})
    }.should raise_error
  end
end

