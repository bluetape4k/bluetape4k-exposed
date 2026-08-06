module Publication
  module InventoryPolicy
    EXPECTED_PLATFORM_PUBLICATIONS = 1

    def self.platform_publication_error(count)
      return nil if count == EXPECTED_PLATFORM_PUBLICATIONS

      "publication inventory must contain exactly #{EXPECTED_PLATFORM_PUBLICATIONS} platform publication " \
        "(found #{count})"
    end
  end
end
